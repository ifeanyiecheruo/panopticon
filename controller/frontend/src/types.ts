export type Route = 'fleet' | 'phone' | 'gallery' | 'trash' | 'add';

export interface AppState {
  route: Route;
  /** Selected device for the 'phone' route (master-detail Fleet). */
  phoneId: string | null;
  /** 'all' or a phone id — persisted here so Phone-detail's "View in Gallery"
   * can pre-filter and the choice survives navigating away and back. */
  galleryFilter: string;
}

export type NavigateArgs = Partial<AppState>;
