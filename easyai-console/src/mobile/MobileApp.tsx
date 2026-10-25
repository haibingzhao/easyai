import React from 'react';
import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import { MobileGate } from './MobileGate';
import { MobileAppLayout } from './MobileAppLayout';
import { MobileChatPage } from './pages/MobileChatPage';
import { MobileHistoryPage } from './pages/MobileHistoryPage';
import { MobileMorePage } from './pages/MobileMorePage';
import { MobileNotificationsPage } from './pages/MobileNotificationsPage';
import { MobileProfilePage } from './pages/MobileProfilePage';
import { MobileToolDetailPage } from './pages/MobileToolDetailPage';
import { MobileArtifactPage } from './pages/MobileArtifactPage';

/**
 * easyai-console's own mobile host — the first consumer of the mobile shell, and the reference
 * wiring for a host app that adds its own `mobile.html` (see `.doc/mobile-ios-design.md`).
 *
 * Hash routing is load-bearing: the document path stays `/mobile.html` no matter where the user
 * navigates, so a PWA installed with `scope: "/"` never tries to open a path the backend does not
 * serve as HTML.
 */
export const MobileApp: React.FC = () => (
  <HashRouter>
    <Routes>
      <Route element={<MobileGate />}>
        <Route element={<MobileAppLayout />}>
          <Route index element={<Navigate to="/chat" replace />} />
          <Route path="/chat" element={<MobileChatPage />} />
          <Route path="/history" element={<MobileHistoryPage />} />
          <Route path="/more" element={<MobileMorePage />} />
          <Route path="/notifications" element={<MobileNotificationsPage />} />
          <Route path="/me" element={<MobileProfilePage />} />
          <Route path="/tool/:toolCallId" element={<MobileToolDetailPage />} />
          <Route path="/artifact" element={<MobileArtifactPage />} />
          <Route path="*" element={<Navigate to="/chat" replace />} />
        </Route>
      </Route>
    </Routes>
  </HashRouter>
);

export default MobileApp;
