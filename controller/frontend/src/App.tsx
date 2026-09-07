import { useState } from 'preact/hooks';
import type { JSX } from 'preact';
import type { AppState, NavigateArgs, Route } from './types';
import { Shell } from './components/Shell';
import { Fleet } from './screens/Fleet';
import { Gallery } from './screens/Gallery';
import { Trash } from './screens/Trash';
import { AddPhone } from './screens/AddPhone';

function initialState(): AppState {
  return {
    route: 'fleet',
    phoneId: null,
    galleryFilter: 'all',
  };
}

export function App() {
  const [state, setState] = useState<AppState>(initialState);

  // Merge a partial state update. Unlike the old vanilla-ported router this does
  // NOT force the active screen to remount/refetch — screens keep their own data
  // across selection changes, so picking a gallery clip or a fleet device no
  // longer flashes a full-screen "Loading…".
  const navigate = (partial: NavigateArgs) => {
    setState((s) => ({ ...s, ...partial }));
  };

  const handleNav = (route: Route) => {
    navigate({ route });
  };

  // 'phone' is master-detail inside Fleet, so it shares Fleet's key (and
  // component instance) — the device master list doesn't remount when you drill
  // into a device.
  const inFleet = state.route === 'fleet' || state.route === 'phone';

  let screen: JSX.Element;
  if (inFleet) {
    screen = (
      <Fleet
        key="fleet"
        selectedPhoneId={state.route === 'phone' ? state.phoneId : null}
        onSelectPhone={(phoneId) => navigate({ route: 'phone', phoneId })}
        onDeselect={() => navigate({ route: 'fleet', phoneId: null })}
        onViewGallery={(phoneId) => navigate({ route: 'gallery', galleryFilter: phoneId })}
        onUnpaired={() => navigate({ route: 'fleet', phoneId: null })}
      />
    );
  } else {
    switch (state.route) {
      case 'gallery':
        screen = (
          <Gallery
            key="gallery"
            galleryFilter={state.galleryFilter}
            onFilterChange={(filter) => navigate({ galleryFilter: filter })}
          />
        );
        break;
      case 'trash':
        screen = <Trash key="trash" />;
        break;
      case 'add':
        screen = <AddPhone key="add" onGoFleet={() => navigate({ route: 'fleet' })} />;
        break;
      default:
        throw new Error(`unhandled route: ${state.route as string}`);
    }
  }

  return (
    <Shell route={state.route} onNav={handleNav}>
      {screen}
    </Shell>
  );
}
