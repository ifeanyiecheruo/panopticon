import { useState } from 'preact/hooks';
import type { JSX } from 'preact';
import type { AppState, NavigateArgs, Route } from './types';
import { Shell } from './components/Shell';
import { Fleet } from './screens/Fleet';
import { PhoneDetail } from './screens/PhoneDetail';
import { Gallery } from './screens/Gallery';
import { Trash } from './screens/Trash';
import { AddPhone } from './screens/AddPhone';

function initialState(): AppState {
  return {
    route: 'fleet',
    phoneId: null,
    galleryFilter: 'all',
    gallerySelected: null,
    trashSelected: null,
    trashConfirmingEmpty: false,
    nonce: 0,
  };
}

export function App() {
  const [state, setState] = useState<AppState>(initialState);

  // Bumps `nonce`, which is folded into the active screen's `key` below —
  // that forces a full unmount/remount of the screen, so it refetches its
  // data from scratch. This is the direct analogue of the vanilla version's
  // navigate() always doing `main.innerHTML = ...` and re-running the
  // relevant renderX(main) function from zero on every single navigation.
  const navigate = (partial: NavigateArgs) => {
    setState((s) => ({ ...s, ...partial, nonce: s.nonce + 1 }));
  };

  // Updates shared state without forcing a remount/refetch — used only for
  // persisting Gallery/Trash's "default-select the first clip" behavior,
  // which in the vanilla version happens inline during a render rather than
  // via a fresh navigate() call.
  const setSilent = (partial: NavigateArgs) => {
    setState((s) => ({ ...s, ...partial }));
  };

  const handleNav = (route: Route) => {
    navigate({ route });
  };

  const mainKey = `${state.route}-${state.nonce}`;

  let screen: JSX.Element;
  switch (state.route) {
    case 'fleet':
      screen = (
        <Fleet
          key={mainKey}
          onSelectPhone={(phoneId) => navigate({ route: 'phone', phoneId })}
          onRefresh={() => navigate({ route: 'fleet' })}
        />
      );
      break;
    case 'phone':
      screen = (
        <PhoneDetail
          key={mainKey}
          phoneId={state.phoneId as string}
          onBack={() => navigate({ route: 'fleet' })}
          onViewGallery={() => navigate({ route: 'gallery', galleryFilter: state.phoneId ?? 'all' })}
        />
      );
      break;
    case 'gallery':
      screen = (
        <Gallery
          key={mainKey}
          galleryFilter={state.galleryFilter}
          gallerySelected={state.gallerySelected}
          onFilterChange={(filter) => navigate({ galleryFilter: filter, gallerySelected: null })}
          onSelectClip={(clipKey) => navigate({ gallerySelected: clipKey })}
          onAutoSelect={(clipKey) => setSilent({ gallerySelected: clipKey })}
          onTrashed={() => navigate({ gallerySelected: null })}
        />
      );
      break;
    case 'trash':
      screen = (
        <Trash
          key={mainKey}
          trashSelected={state.trashSelected}
          trashConfirmingEmpty={state.trashConfirmingEmpty}
          onSelectClip={(clipKey) => navigate({ trashSelected: clipKey })}
          onAutoSelect={(clipKey) => setSilent({ trashSelected: clipKey })}
          onOpenConfirmEmpty={() => navigate({ trashConfirmingEmpty: true })}
          onCancelConfirmEmpty={() => navigate({ trashConfirmingEmpty: false })}
          onEmptied={() => navigate({ trashConfirmingEmpty: false, trashSelected: null })}
          onRestored={() => navigate({ trashSelected: null })}
          onDeleted={() => navigate({ trashSelected: null })}
        />
      );
      break;
    case 'add':
      screen = <AddPhone key={mainKey} onGoFleet={() => navigate({ route: 'fleet' })} />;
      break;
    default: {
      const _exhaustive: never = state.route;
      throw new Error(`unhandled route: ${_exhaustive}`);
    }
  }

  return (
    <Shell route={state.route} onNav={handleNav}>
      {screen}
    </Shell>
  );
}
