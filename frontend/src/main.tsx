import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { SessionProvider } from './session';
import { LanguageProvider } from './i18n';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <LanguageProvider>
        <SessionProvider>
          <App />
        </SessionProvider>
      </LanguageProvider>
    </BrowserRouter>
  </StrictMode>,
);
