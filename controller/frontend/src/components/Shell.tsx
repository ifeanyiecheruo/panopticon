import type { ComponentChildren } from 'preact';
import type { Route } from '../types';
import { LogoIcon, FleetIcon, GalleryIcon, TrashIcon, AddIcon } from '../lib/icons';

interface ShellProps {
  route: Route;
  onNav: (route: Route) => void;
  children: ComponentChildren;
}

export function Shell({ route, onNav, children }: ShellProps) {
  return (
    <div className="app-shell">
      <div className="rail">
        <div className="brand">
          <LogoIcon /> Panopticon
        </div>
        <button
          className={`rail-btn ${route === 'fleet' || route === 'phone' ? 'active' : ''}`}
          onClick={() => onNav('fleet')}
        >
          <FleetIcon /> Fleet
        </button>
        <button className={`rail-btn ${route === 'gallery' ? 'active' : ''}`} onClick={() => onNav('gallery')}>
          <GalleryIcon /> Gallery
        </button>
        <button className={`rail-btn ${route === 'trash' ? 'active' : ''}`} onClick={() => onNav('trash')}>
          <TrashIcon /> Trash
        </button>
        <button className={`rail-btn ${route === 'add' ? 'active' : ''}`} onClick={() => onNav('add')}>
          <AddIcon /> Add phone
        </button>
        <div className="rail-spacer"></div>
        <div className="rail-foot">v0.1 · tray running</div>
      </div>
      <div className="main" id="main">
        {children}
      </div>
    </div>
  );
}
