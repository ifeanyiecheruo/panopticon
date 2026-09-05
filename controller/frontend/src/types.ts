export type Route = 'fleet' | 'phone' | 'gallery' | 'trash' | 'add';

export interface AppState {
  route: Route;
  phoneId: string | null;
  galleryFilter: string; // 'all' or a phone id
  gallerySelected: string | null; // clipKey
  trashSelected: string | null; // clipKey
  trashConfirmingEmpty: boolean;
  /** Bumped on every navigate() call so the active screen remounts and
   * refetches its data — this is what stands in for the vanilla version
   * always doing `main.innerHTML = ...; await renderX(main)` from scratch
   * on every single navigation, including re-selecting within the same
   * screen (e.g. picking a different gallery clip re-fetches the clip
   * list). Faithful-but-wasteful, same as the original. */
  nonce: number;
}

export type NavigateArgs = Partial<Omit<AppState, 'nonce'>>;
