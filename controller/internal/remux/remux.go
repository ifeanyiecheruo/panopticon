// Package remux rewraps an archived segment - the progressive MP4 Android's
// MediaMuxer writes (ftyp, moov with full sample tables, mdat) - as fragmented
// MP4: an init segment (ftyp + moov with mvex, no samples) followed by one
// moof+mdat fragment per GOP. That is the only shape Media Source Extensions
// accept, and it is what lets the gallery play a clip's segments - and a
// play-through across clips - as one stream on one <video>
// (docs/plan/gallery-mse-playback.md).
//
// It is a remux, not a transcode: the H.264 samples are copied byte for byte,
// only the boxes around them change. Built for what the phone records (one
// H.264 video track, no audio), so anything else is ErrUnsupported rather than
// a best effort.
package remux

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

// ErrUnsupported means the file is a valid MP4 this package does not handle:
// no video track, or a codec other than H.264.
var ErrUnsupported = errors.New("remux: unsupported mp4")

// Fragmented is a segment ready to stream as fMP4. Building one reads only
// the source's moov; WriteTo copies the sample data across from the source.
type Fragmented struct {
	// Codec is the RFC 6381 codec string MSE's addSourceBuffer wants, e.g.
	// "avc1.42c01f".
	Codec string
	// Duration is the track's length in seconds, from its sample durations.
	Duration float64

	src   io.ReaderAt
	init  []byte
	frags []fragment
}

type fragment struct {
	header []byte // moof + mdat box header
	ranges []byteRange
}

type byteRange struct{ off, n int64 }

// Size is the total length WriteTo writes.
func (f *Fragmented) Size() int64 {
	n := int64(len(f.init))
	for _, fr := range f.frags {
		n += int64(len(fr.header))
		for _, r := range fr.ranges {
			n += r.n
		}
	}
	return n
}

// WriteTo writes the init segment and then every fragment.
func (f *Fragmented) WriteTo(w io.Writer) (int64, error) {
	var total int64
	n, err := w.Write(f.init)
	total += int64(n)
	if err != nil {
		return total, err
	}
	for _, fr := range f.frags {
		n, err := w.Write(fr.header)
		total += int64(n)
		if err != nil {
			return total, err
		}
		for _, r := range fr.ranges {
			c, err := io.Copy(w, io.NewSectionReader(f.src, r.off, r.n))
			total += c
			if err != nil {
				return total, err
			}
		}
	}
	return total, nil
}

// track is what Open pulls out of the source's video trak.
type track struct {
	timescale uint32
	matrix    []byte // tkhd's 36-byte transform (carries any rotation)
	width     []byte // tkhd's 16.16 width and height, as stored
	height    []byte
	stsd      []byte // the whole stsd box, copied verbatim into the init segment
	codec     string

	durs    []uint32 // per sample
	ctos    []int32  // per sample; nil when there is no ctts
	sizes   []uint32
	offsets []int64
	sync    []bool // nil when there is no stss (every sample is a sync sample)
}

// Open reads src's moov and lays out the fragmented version of it.
func Open(src io.ReaderAt, size int64) (*Fragmented, error) {
	moov, err := readTopLevel(src, size, "moov")
	if err != nil {
		return nil, err
	}
	tr, err := parseVideoTrak(moov)
	if err != nil {
		return nil, err
	}
	f := &Fragmented{Codec: tr.codec, src: src, init: buildInit(tr)}

	var decode uint64
	seq := uint32(1)
	n := len(tr.sizes)
	for start := 0; start < n; {
		end := start + 1
		for end < n && !(tr.sync == nil || tr.sync[end]) {
			end++
		}
		// With no stss every sample is a sync sample; group them about a second
		// at a time rather than one fragment per frame.
		if tr.sync == nil {
			end = min(n, start+30)
		}
		for i := start; i < end; i++ {
			if tr.offsets[i] < 0 || tr.offsets[i]+int64(tr.sizes[i]) > size {
				return nil, fmt.Errorf("remux: sample %d lies outside the file", i+1)
			}
		}
		f.frags = append(f.frags, buildFragment(tr, seq, decode, start, end))
		for i := start; i < end; i++ {
			decode += uint64(tr.durs[i])
		}
		seq++
		start = end
	}
	if tr.timescale > 0 {
		f.Duration = float64(decode) / float64(tr.timescale)
	}
	return f, nil
}

// readTopLevel returns the body of the first top-level box of type typ.
func readTopLevel(src io.ReaderAt, size int64, typ string) ([]byte, error) {
	var hdr [16]byte
	for off := int64(0); off+8 <= size; {
		if _, err := src.ReadAt(hdr[:8], off); err != nil {
			return nil, err
		}
		boxSize := int64(binary.BigEndian.Uint32(hdr[:4]))
		hlen := int64(8)
		switch boxSize {
		case 1:
			if _, err := src.ReadAt(hdr[8:16], off+8); err != nil {
				return nil, err
			}
			boxSize = int64(binary.BigEndian.Uint64(hdr[8:16]))
			hlen = 16
		case 0:
			boxSize = size - off
		}
		if boxSize < hlen || off+boxSize > size {
			return nil, fmt.Errorf("remux: bad %q box at %d", hdr[4:8], off)
		}
		if string(hdr[4:8]) == typ {
			if boxSize > 64<<20 {
				return nil, fmt.Errorf("remux: %s box of %d bytes", typ, boxSize)
			}
			body := make([]byte, boxSize-hlen)
			if _, err := src.ReadAt(body, off+hlen); err != nil {
				return nil, err
			}
			return body, nil
		}
		off += boxSize
	}
	return nil, fmt.Errorf("remux: no %s box", typ)
}

// child is one box inside a parent's body.
type child struct {
	typ  string
	raw  []byte // header included
	body []byte
}

func children(b []byte) ([]child, error) {
	var out []child
	for len(b) > 0 {
		if len(b) < 8 {
			return nil, errors.New("remux: truncated box header")
		}
		size := uint64(binary.BigEndian.Uint32(b))
		hlen := uint64(8)
		switch size {
		case 1:
			if len(b) < 16 {
				return nil, errors.New("remux: truncated box header")
			}
			size = binary.BigEndian.Uint64(b[8:])
			hlen = 16
		case 0:
			size = uint64(len(b))
		}
		if size < hlen || size > uint64(len(b)) {
			return nil, fmt.Errorf("remux: bad %q box", b[4:8])
		}
		out = append(out, child{typ: string(b[4:8]), raw: b[:size], body: b[hlen:size]})
		b = b[size:]
	}
	return out, nil
}

// find walks a path of box types down from b and returns the body of the
// first match, or nil.
func find(b []byte, path ...string) []byte {
	for _, typ := range path {
		kids, err := children(b)
		if err != nil {
			return nil
		}
		var next []byte
		for _, k := range kids {
			if k.typ == typ {
				next = k.body
				break
			}
		}
		if next == nil {
			return nil
		}
		b = next
	}
	return b
}

func parseVideoTrak(moov []byte) (*track, error) {
	kids, err := children(moov)
	if err != nil {
		return nil, err
	}
	for _, k := range kids {
		if k.typ != "trak" {
			continue
		}
		if hdlr := find(k.body, "mdia", "hdlr"); len(hdlr) >= 12 && string(hdlr[8:12]) == "vide" {
			return parseTrak(k.body)
		}
	}
	return nil, fmt.Errorf("%w: no video track", ErrUnsupported)
}

func parseTrak(trak []byte) (*track, error) {
	tr := &track{}

	tkhd := find(trak, "tkhd")
	matrixAt := 40 // v0: version/flags, 4 x 32-bit times/ids, reserved, layer..volume
	if len(tkhd) > 0 && tkhd[0] == 1 {
		matrixAt = 52 // v1 widens creation/modification time and duration to 64 bits
	}
	if len(tkhd) < matrixAt+44 {
		return nil, errors.New("remux: short tkhd")
	}
	tr.matrix = tkhd[matrixAt : matrixAt+36]
	tr.width = tkhd[matrixAt+36 : matrixAt+40]
	tr.height = tkhd[matrixAt+40 : matrixAt+44]

	mdhd := find(trak, "mdia", "mdhd")
	switch {
	case len(mdhd) >= 16 && mdhd[0] == 0:
		tr.timescale = binary.BigEndian.Uint32(mdhd[12:])
	case len(mdhd) >= 24 && mdhd[0] == 1:
		tr.timescale = binary.BigEndian.Uint32(mdhd[20:])
	default:
		return nil, errors.New("remux: bad mdhd")
	}

	stbl := find(trak, "mdia", "minf", "stbl")
	if stbl == nil {
		return nil, errors.New("remux: no stbl")
	}
	boxes, err := children(stbl)
	if err != nil {
		return nil, err
	}
	tables := map[string][]byte{}
	for _, b := range boxes {
		if b.typ == "stsd" {
			tr.stsd = b.raw
		}
		tables[b.typ] = b.body
	}
	if tr.stsd == nil {
		return nil, errors.New("remux: no stsd")
	}
	if tr.codec, err = avcCodec(tables["stsd"]); err != nil {
		return nil, err
	}

	if tr.sizes, err = parseStsz(tables["stsz"]); err != nil {
		return nil, err
	}
	n := len(tr.sizes)
	if n == 0 {
		return nil, errors.New("remux: no samples")
	}
	if tr.durs, err = parseStts(tables["stts"], n); err != nil {
		return nil, err
	}
	if ctts, ok := tables["ctts"]; ok {
		if tr.ctos, err = parseCtts(ctts, n); err != nil {
			return nil, err
		}
	}
	if stss, ok := tables["stss"]; ok {
		if tr.sync, err = parseStss(stss, n); err != nil {
			return nil, err
		}
	}
	chunks, err := parseChunkOffsets(tables["stco"], tables["co64"])
	if err != nil {
		return nil, err
	}
	if tr.offsets, err = sampleOffsets(tables["stsc"], chunks, tr.sizes); err != nil {
		return nil, err
	}
	return tr, nil
}

// avcCodec returns "avc1.PPCCLL" from the first sample entry's avcC.
func avcCodec(stsd []byte) (string, error) {
	if len(stsd) < 8 {
		return "", errors.New("remux: short stsd")
	}
	entries, err := children(stsd[8:])
	if err != nil || len(entries) == 0 {
		return "", errors.New("remux: no sample entry")
	}
	e := entries[0]
	if e.typ != "avc1" && e.typ != "avc3" {
		return "", fmt.Errorf("%w: codec %s", ErrUnsupported, e.typ)
	}
	// A visual sample entry's fixed fields take 78 bytes before its child boxes.
	if len(e.body) < 78 {
		return "", errors.New("remux: short avc1")
	}
	avcC := find(e.body[78:], "avcC")
	if len(avcC) < 4 {
		return "", errors.New("remux: no avcC")
	}
	return fmt.Sprintf("%s.%02x%02x%02x", e.typ, avcC[1], avcC[2], avcC[3]), nil
}

// fullBoxCount checks a full box's version/flags + entry count header and
// that count entries of width bytes follow it.
func fullBoxCount(b []byte, name string, width int) (int, error) {
	if len(b) < 8 {
		return 0, fmt.Errorf("remux: short %s", name)
	}
	count := int(binary.BigEndian.Uint32(b[4:]))
	if count < 0 || len(b)-8 < count*width {
		return 0, fmt.Errorf("remux: truncated %s", name)
	}
	return count, nil
}

func parseStsz(b []byte) ([]uint32, error) {
	if len(b) < 12 {
		return nil, errors.New("remux: no stsz")
	}
	fixed := binary.BigEndian.Uint32(b[4:])
	count := int(binary.BigEndian.Uint32(b[8:]))
	if fixed == 0 && len(b)-12 < count*4 {
		return nil, errors.New("remux: truncated stsz")
	}
	sizes := make([]uint32, count)
	for i := range sizes {
		if fixed != 0 {
			sizes[i] = fixed
		} else {
			sizes[i] = binary.BigEndian.Uint32(b[12+i*4:])
		}
	}
	return sizes, nil
}

func parseStts(b []byte, n int) ([]uint32, error) {
	count, err := fullBoxCount(b, "stts", 8)
	if err != nil {
		return nil, err
	}
	durs := make([]uint32, 0, n)
	for i := 0; i < count; i++ {
		run := int(binary.BigEndian.Uint32(b[8+i*8:]))
		delta := binary.BigEndian.Uint32(b[12+i*8:])
		for j := 0; j < run && len(durs) < n; j++ {
			durs = append(durs, delta)
		}
	}
	if len(durs) != n {
		return nil, fmt.Errorf("remux: stts covers %d of %d samples", len(durs), n)
	}
	return durs, nil
}

func parseCtts(b []byte, n int) ([]int32, error) {
	count, err := fullBoxCount(b, "ctts", 8)
	if err != nil {
		return nil, err
	}
	ctos := make([]int32, 0, n)
	for i := 0; i < count; i++ {
		run := int(binary.BigEndian.Uint32(b[8+i*8:]))
		// v0 declares the offset unsigned and v1 signed; MediaMuxer only ever
		// writes small positive ones, so reading both as signed is the same.
		off := int32(binary.BigEndian.Uint32(b[12+i*8:]))
		for j := 0; j < run && len(ctos) < n; j++ {
			ctos = append(ctos, off)
		}
	}
	if len(ctos) != n {
		return nil, fmt.Errorf("remux: ctts covers %d of %d samples", len(ctos), n)
	}
	return ctos, nil
}

func parseStss(b []byte, n int) ([]bool, error) {
	count, err := fullBoxCount(b, "stss", 4)
	if err != nil {
		return nil, err
	}
	sync := make([]bool, n)
	for i := 0; i < count; i++ {
		s := int(binary.BigEndian.Uint32(b[8+i*4:]))
		if s >= 1 && s <= n {
			sync[s-1] = true
		}
	}
	return sync, nil
}

func parseChunkOffsets(stco, co64 []byte) ([]int64, error) {
	if co64 != nil {
		count, err := fullBoxCount(co64, "co64", 8)
		if err != nil {
			return nil, err
		}
		out := make([]int64, count)
		for i := range out {
			out[i] = int64(binary.BigEndian.Uint64(co64[8+i*8:]))
		}
		return out, nil
	}
	count, err := fullBoxCount(stco, "stco", 4)
	if err != nil {
		return nil, err
	}
	out := make([]int64, count)
	for i := range out {
		out[i] = int64(binary.BigEndian.Uint32(stco[8+i*4:]))
	}
	return out, nil
}

// sampleOffsets places every sample in the file: stsc says how many samples
// each chunk holds, the chunk offsets where each chunk starts, and a chunk's
// samples sit back to back.
func sampleOffsets(stsc []byte, chunks []int64, sizes []uint32) ([]int64, error) {
	count, err := fullBoxCount(stsc, "stsc", 12)
	if err != nil {
		return nil, err
	}
	offsets := make([]int64, 0, len(sizes))
	for e := 0; e < count; e++ {
		first := int(binary.BigEndian.Uint32(stsc[8+e*12:]))
		perChunk := int(binary.BigEndian.Uint32(stsc[12+e*12:]))
		last := len(chunks) // this run of chunks ends where the next entry's starts
		if e+1 < count {
			last = int(binary.BigEndian.Uint32(stsc[8+(e+1)*12:])) - 1
		}
		if first < 1 || last > len(chunks) {
			return nil, errors.New("remux: stsc names a chunk that doesn't exist")
		}
		for c := first; c <= last; c++ {
			off := chunks[c-1]
			for s := 0; s < perChunk && len(offsets) < len(sizes); s++ {
				offsets = append(offsets, off)
				off += int64(sizes[len(offsets)-1])
			}
		}
	}
	if len(offsets) != len(sizes) {
		return nil, fmt.Errorf("remux: chunks place %d of %d samples", len(offsets), len(sizes))
	}
	return offsets, nil
}

// ---- writing ----

func box(typ string, parts ...[]byte) []byte {
	n := 8
	for _, p := range parts {
		n += len(p)
	}
	out := make([]byte, 0, n)
	out = binary.BigEndian.AppendUint32(out, uint32(n))
	out = append(out, typ...)
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}

func fullBox(typ string, version byte, flags uint32, parts ...[]byte) []byte {
	vf := binary.BigEndian.AppendUint32(nil, uint32(version)<<24|flags)
	return box(typ, append([][]byte{vf}, parts...)...)
}

func u32s(vs ...uint32) []byte {
	out := make([]byte, 0, 4*len(vs))
	for _, v := range vs {
		out = binary.BigEndian.AppendUint32(out, v)
	}
	return out
}

var identityMatrix = u32s(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)

func buildInit(tr *track) []byte {
	ftyp := box("ftyp", []byte("isom"), u32s(0x200), []byte("isomiso6avc1mp41"))

	mvhd := fullBox("mvhd", 0, 0,
		u32s(0, 0, tr.timescale, 0), // creation, modification, timescale, duration
		u32s(0x10000), []byte{1, 0}, make([]byte, 10), // rate 1.0, volume 1.0, reserved
		identityMatrix, make([]byte, 24), // pre_defined
		u32s(2)) // next_track_ID
	tkhd := fullBox("tkhd", 0, 3, // enabled | in movie
		u32s(0, 0, 1, 0, 0), // creation, modification, track_ID, reserved, duration
		make([]byte, 16),    // reserved, layer, alternate_group, volume, reserved
		tr.matrix, tr.width, tr.height)
	mdhd := fullBox("mdhd", 0, 0, u32s(0, 0, tr.timescale, 0), []byte{0x55, 0xc4, 0, 0}) // language "und"
	hdlr := fullBox("hdlr", 0, 0, u32s(0), []byte("vide"), make([]byte, 12), []byte("VideoHandler\x00"))
	vmhd := fullBox("vmhd", 0, 1, make([]byte, 8))
	dinf := box("dinf", fullBox("dref", 0, 0, u32s(1), fullBox("url ", 0, 1)))
	stbl := box("stbl", tr.stsd,
		fullBox("stts", 0, 0, u32s(0)),
		fullBox("stsc", 0, 0, u32s(0)),
		fullBox("stsz", 0, 0, u32s(0, 0)),
		fullBox("stco", 0, 0, u32s(0)))
	trak := box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", vmhd, dinf, stbl)))
	mvex := box("mvex", fullBox("trex", 0, 0, u32s(1, 1, 0, 0, 0))) // track 1, sample description 1

	return append(ftyp, box("moov", mvhd, trak, mvex)...)
}

const (
	trunDataOffset   = 0x001
	trunSampleDur    = 0x100
	trunSampleSize   = 0x200
	trunSampleFlags  = 0x400
	trunSampleCTO    = 0x800
	tfhdBaseIsMoof   = 0x020000
	sampleFlagsSync  = 0x02000000 // sample_depends_on = 2 (an I frame)
	sampleFlagsOther = 0x01010000 // sample_depends_on = 1, sample_is_non_sync_sample
)

// buildFragment lays out samples [start, end) as one moof plus the header of
// the mdat that carries them; the sample bytes come from the source at write
// time.
func buildFragment(tr *track, seq uint32, decode uint64, start, end int) fragment {
	flags := uint32(trunDataOffset | trunSampleDur | trunSampleSize | trunSampleFlags)
	if tr.ctos != nil {
		flags |= trunSampleCTO
	}
	entries := u32s(uint32(end-start), 0) // sample_count, data_offset (patched below)
	var dataLen int64
	var ranges []byteRange
	for i := start; i < end; i++ {
		sf := uint32(sampleFlagsOther)
		if tr.sync == nil || tr.sync[i] {
			sf = sampleFlagsSync
		}
		entries = binary.BigEndian.AppendUint32(entries, tr.durs[i])
		entries = binary.BigEndian.AppendUint32(entries, tr.sizes[i])
		entries = binary.BigEndian.AppendUint32(entries, sf)
		if tr.ctos != nil {
			entries = binary.BigEndian.AppendUint32(entries, uint32(tr.ctos[i]))
		}
		off, n := tr.offsets[i], int64(tr.sizes[i])
		dataLen += n
		// MediaMuxer writes one chunk, so this is nearly always a single range.
		if k := len(ranges) - 1; k >= 0 && ranges[k].off+ranges[k].n == off {
			ranges[k].n += n
		} else {
			ranges = append(ranges, byteRange{off, n})
		}
	}
	// v1 so the composition offsets read as signed.
	trun := fullBox("trun", 1, flags, entries)
	moof := box("moof",
		fullBox("mfhd", 0, 0, u32s(seq)),
		box("traf",
			fullBox("tfhd", 0, tfhdBaseIsMoof, u32s(1)),
			fullBox("tfdt", 1, 0, binary.BigEndian.AppendUint64(nil, decode)),
			trun))
	// data_offset counts from the start of the moof to the first sample byte,
	// just past the mdat header. It sits after trun's header, version/flags and
	// sample_count, and trun is the last box in the moof.
	binary.BigEndian.PutUint32(moof[len(moof)-len(trun)+16:], uint32(len(moof)+8))
	header := binary.BigEndian.AppendUint32(moof, uint32(8+dataLen))
	header = append(header, "mdat"...)
	return fragment{header: header, ranges: ranges}
}
