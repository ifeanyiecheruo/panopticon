package remux

import (
	"bytes"
	"encoding/binary"
	"errors"
	"testing"
)

// progressive builds a minimal MediaMuxer-shaped MP4: ftyp, moov, mdat, one
// H.264 track. Sample i's bytes are all byte(i+1) so the output can be checked
// sample by sample. chunkSplit puts the samples in two chunks (exercising
// stsc) instead of one.
func progressive(t *testing.T, sizes []uint32, syncs []uint32, ctts bool, chunkSplit int) []byte {
	t.Helper()
	var data []byte
	for i, s := range sizes {
		data = append(data, bytes.Repeat([]byte{byte(i + 1)}, int(s))...)
	}

	avcC := box("avcC", []byte{1, 0x42, 0xc0, 0x1f, 0xff, 0xe0, 0x00})
	avc1 := box("avc1", make([]byte, 78), avcC)
	stsd := fullBox("stsd", 0, 0, u32s(1), avc1)
	stts := fullBox("stts", 0, 0, u32s(1, uint32(len(sizes)), 3000))
	stsz := fullBox("stsz", 0, 0, u32s(0, uint32(len(sizes))), u32s(sizes...))
	var stss []byte
	if syncs != nil {
		stss = fullBox("stss", 0, 0, u32s(uint32(len(syncs))), u32s(syncs...))
	}
	var cttsBox []byte
	if ctts {
		cttsBox = fullBox("ctts", 0, 0, u32s(1, uint32(len(sizes)), 6000))
	}

	tkhd := fullBox("tkhd", 0, 3, make([]byte, 36),
		u32s(0, 0x10000, 0, 0xffff0000, 0, 0, 0, 0, 0x40000000), // a 90° rotation
		u32s(1024<<16, 576<<16))
	mdhd := fullBox("mdhd", 0, 0, u32s(0, 0, 90000, 0), u32s(0))
	hdlr := fullBox("hdlr", 0, 0, u32s(0), []byte("vide"), make([]byte, 12), []byte("v\x00"))

	ftyp := box("ftyp", []byte("isom"), u32s(0))
	build := func(mdatOff uint32) []byte {
		var stsc, co []byte
		if chunkSplit > 0 {
			stsc = fullBox("stsc", 0, 0, u32s(2, 1, uint32(chunkSplit), 1, 2, uint32(len(sizes)-chunkSplit), 1))
			var second uint32
			for _, s := range sizes[:chunkSplit] {
				second += s
			}
			co = fullBox("stco", 0, 0, u32s(2, mdatOff, mdatOff+second))
		} else {
			stsc = fullBox("stsc", 0, 0, u32s(1, 1, uint32(len(sizes)), 1))
			co = fullBox("co64", 0, 0, u32s(1), binary.BigEndian.AppendUint64(nil, uint64(mdatOff)))
		}
		stbl := box("stbl", stsd, stts, stss, cttsBox, stsz, stsc, co)
		trak := box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", stbl)))
		moov := box("moov", fullBox("mvhd", 0, 0, make([]byte, 96)), trak)
		return append(append([]byte{}, ftyp...), moov...)
	}
	head := build(0)
	head = build(uint32(len(head) + 8)) // same length; now with the real offsets
	return append(head, box("mdat", data)...)
}

type sample struct {
	dur, flags uint32
	cto        int32
	data       []byte
	decode     uint64
}

// parseFragmented checks the output's top-level layout and returns its
// samples in order.
func parseFragmented(t *testing.T, out []byte) (moov []byte, samples []sample) {
	t.Helper()
	top, err := children(out)
	if err != nil {
		t.Fatal(err)
	}
	if len(top) < 4 || top[0].typ != "ftyp" || top[1].typ != "moov" {
		t.Fatalf("output starts %v, want ftyp moov", types(top))
	}
	for i := 2; i < len(top); i += 2 {
		moof, mdat := top[i], top[i+1]
		if moof.typ != "moof" || mdat.typ != "mdat" {
			t.Fatalf("box %d,%d = %s %s, want moof mdat", i, i+1, moof.typ, mdat.typ)
		}
		tfdt := find(moof.body, "traf", "tfdt")
		trun := find(moof.body, "traf", "trun")
		decode := binary.BigEndian.Uint64(tfdt[4:])
		flags := binary.BigEndian.Uint32(trun) & 0xffffff
		n := int(binary.BigEndian.Uint32(trun[4:]))
		dataOff := int(binary.BigEndian.Uint32(trun[8:]))
		if dataOff != len(moof.raw)+8 {
			t.Fatalf("data_offset %d, want %d", dataOff, len(moof.raw)+8)
		}
		pos, entry := 0, trun[12:]
		for j := 0; j < n; j++ {
			s := sample{decode: decode}
			s.dur = binary.BigEndian.Uint32(entry)
			size := binary.BigEndian.Uint32(entry[4:])
			s.flags = binary.BigEndian.Uint32(entry[8:])
			entry = entry[12:]
			if flags&trunSampleCTO != 0 {
				s.cto = int32(binary.BigEndian.Uint32(entry))
				entry = entry[4:]
			}
			s.data = mdat.body[pos : pos+int(size)]
			pos += int(size)
			decode += uint64(s.dur)
			samples = append(samples, s)
		}
		if pos != len(mdat.body) {
			t.Fatalf("fragment %d: trun covers %d of %d mdat bytes", i/2, pos, len(mdat.body))
		}
	}
	return top[1].body, samples
}

func types(cs []child) []string {
	var out []string
	for _, c := range cs {
		out = append(out, c.typ)
	}
	return out
}

func remux(t *testing.T, src []byte) (*Fragmented, []byte) {
	t.Helper()
	f, err := Open(bytes.NewReader(src), int64(len(src)))
	if err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	n, err := f.WriteTo(&out)
	if err != nil {
		t.Fatal(err)
	}
	if n != f.Size() || int64(out.Len()) != n {
		t.Fatalf("wrote %d (buffer %d), Size() says %d", n, out.Len(), f.Size())
	}
	return f, out.Bytes()
}

func TestRemuxFragmentsPerGOPAndKeepsEverySample(t *testing.T) {
	for _, tc := range []struct {
		name       string
		chunkSplit int
		ctts       bool
	}{
		{"one chunk", 0, false},
		{"two chunks with ctts", 4, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			sizes := []uint32{50, 10, 11, 12, 40, 9, 8}
			src := progressive(t, sizes, []uint32{1, 5}, tc.ctts, tc.chunkSplit)
			f, out := remux(t, src)

			if f.Codec != "avc1.42c01f" {
				t.Errorf("codec %q", f.Codec)
			}
			if want := float64(len(sizes)*3000) / 90000; f.Duration != want {
				t.Errorf("duration %v, want %v", f.Duration, want)
			}

			moov, samples := parseFragmented(t, out)
			if find(moov, "mvex", "trex") == nil {
				t.Error("init segment has no mvex/trex")
			}
			if stsz := find(moov, "trak", "mdia", "minf", "stbl", "stsz"); binary.BigEndian.Uint32(stsz[8:]) != 0 {
				t.Error("init segment carries samples")
			}
			if tkhd := find(moov, "trak", "tkhd"); binary.BigEndian.Uint32(tkhd[44:]) != 0x10000 {
				t.Error("tkhd rotation matrix not carried over")
			}

			top, _ := children(out)
			if frags := (len(top) - 2) / 2; frags != 2 {
				t.Errorf("%d fragments, want one per GOP (2)", frags)
			}
			if len(samples) != len(sizes) {
				t.Fatalf("%d samples, want %d", len(samples), len(sizes))
			}
			for i, s := range samples {
				if !bytes.Equal(s.data, bytes.Repeat([]byte{byte(i + 1)}, int(sizes[i]))) {
					t.Errorf("sample %d data differs", i)
				}
				if s.decode != uint64(i*3000) || s.dur != 3000 {
					t.Errorf("sample %d decode %d dur %d", i, s.decode, s.dur)
				}
				wantSync := i == 0 || i == 4
				if (s.flags == sampleFlagsSync) != wantSync {
					t.Errorf("sample %d flags %#x, sync want %v", i, s.flags, wantSync)
				}
				if tc.ctts && s.cto != 6000 {
					t.Errorf("sample %d cto %d", i, s.cto)
				}
			}
		})
	}
}

func TestRemuxRejectsNonH264(t *testing.T) {
	src := progressive(t, []uint32{10}, nil, false, 0)
	i := bytes.Index(src, []byte("avc1"))
	copy(src[i:], "hvc1")
	if _, err := Open(bytes.NewReader(src), int64(len(src))); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("err = %v, want ErrUnsupported", err)
	}
}

func TestRemuxRejectsSamplesPastEndOfFile(t *testing.T) {
	src := progressive(t, []uint32{10, 10}, nil, false, 0)
	if _, err := Open(bytes.NewReader(src[:len(src)-5]), int64(len(src)-5)); err == nil {
		t.Fatal("truncated file remuxed without error")
	}
}
