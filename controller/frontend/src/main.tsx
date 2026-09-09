import './style.css';

import { render } from 'preact';
import { App } from './App';

// ---------------------------------------------------------------------
// Panopticon controller — vertical-slice UI.
//
// Screens implemented: Fleet, Phone detail (stub: raw status/config),
// Gallery, Trash, Add phone. Deferred (see docs/status/README.md): live preview
// adjusters, calibration UI, per-clip Trash-vs-eviction-probe niceties.
// ---------------------------------------------------------------------

const app = document.querySelector('#app');
if (!app) {
  throw new Error('#app root element not found');
}

render(<App />, app);
