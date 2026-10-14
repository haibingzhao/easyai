import React from 'react';
import { MobileSheet } from '@/mobile/components/MobileSheet';
import { ConnectionReport } from './ConnectionReport';

/** Mobile surface for the diagnostics: the same report body as the desktop dropdown. */
export const ConnectionSheet: React.FC<{ open: boolean; onClose: () => void }> = ({ open, onClose }) => (
  <MobileSheet open={open} onClose={onClose} title="Network diagnostics" detent="half">
    <ConnectionReport />
  </MobileSheet>
);
