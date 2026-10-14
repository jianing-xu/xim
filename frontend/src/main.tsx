import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { initializePlatform } from './platform/transport';
import './styles.css';

void initializePlatform().then(() => {
  createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>);
});
