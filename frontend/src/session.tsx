import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, tokenStore } from './api/client';
import type { Identity, Role } from './api/types';

interface Session {
  identity: Identity | null;
  loading: boolean;
  signIn: (token: string) => Promise<void>;
  signOut: () => void;
  can: (role: Role) => boolean;
}

const SessionContext = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [loading, setLoading] = useState(() => tokenStore.get() !== null);

  useEffect(() => {
    if (!tokenStore.get()) return;
    api.me().then(setIdentity).catch(() => tokenStore.clear()).finally(() => setLoading(false));
  }, []);

  const signIn = useCallback(async (token: string) => {
    const me = await api.me(token);
    tokenStore.set(token);
    setIdentity(me);
  }, []);
  const signOut = useCallback(() => { tokenStore.clear(); setIdentity(null); }, []);
  const can = useCallback((role: Role) => identity?.roles.includes(role) ?? false, [identity]);

  return <SessionContext.Provider value={{ identity, loading, signIn, signOut, can }}>{children}</SessionContext.Provider>;
}

export function useSession(): Session {
  const session = useContext(SessionContext);
  if (!session) throw new Error('useSession must be used inside SessionProvider');
  return session;
}
