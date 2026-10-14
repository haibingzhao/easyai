import React from 'react';
import { AtSign, Camera, FileText, Images, Slash } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import { MobileSheet } from './MobileSheet';
import { MobileIcon } from './MobileIcon';
import { MOBILE_COMPOSER_PLUS_ITEMS, type MobileComposerContext } from '../registries';

interface ComposerPlusMenuProps {
  open: boolean;
  onClose: () => void;
  ctx: MobileComposerContext;
  onPickPhotos: () => void;
  onPickCamera: () => void;
  onPickFiles: () => void;
  onMention: () => void;
  onCommands: () => void;
}

/**
 * The composer "+" menu.
 *
 * Desktop hangs these off a paperclip and an `@` keystroke. On a phone the paperclip is a 28px
 * target and typing `@` on the iOS keyboard means shifting layers, so both move into one menu of
 * 44pt rows. Host entries append below — a host adds a composer capability without knowing how the
 * editor is built, because it only gets `addText` / `addAttachment`.
 */
export const ComposerPlusMenu: React.FC<ComposerPlusMenuProps> = ({
  open,
  onClose,
  ctx,
  onPickPhotos,
  onPickCamera,
  onPickFiles,
  onMention,
  onCommands,
}) => {
  const items = MOBILE_COMPOSER_PLUS_ITEMS.filter((item) => item.visible?.() !== false);

  const run = (action: () => void | Promise<void>) => {
    onClose();
    void action();
  };

  return (
    <MobileSheet open={open} onClose={onClose} title="Add">
      <button className="mobile-row w-full text-left border-b border-border" onClick={() => run(onPickPhotos)}>
        <Images className="w-5 h-5 shrink-0 text-muted-foreground" />
        <span className="text-sm">{i18n('Photo Library')}</span>
      </button>
      <button className="mobile-row w-full text-left border-b border-border" onClick={() => run(onPickCamera)}>
        <Camera className="w-5 h-5 shrink-0 text-muted-foreground" />
        <span className="text-sm">{i18n('Take Photo')}</span>
      </button>
      <button className="mobile-row w-full text-left border-b border-border" onClick={() => run(onPickFiles)}>
        <FileText className="w-5 h-5 shrink-0 text-muted-foreground" />
        <span className="text-sm">{i18n('Files')}</span>
      </button>
      <button className="mobile-row w-full text-left border-b border-border" onClick={() => run(onMention)}>
        <AtSign className="w-5 h-5 shrink-0 text-muted-foreground" />
        <span className="text-sm">{i18n('Mention a file')}</span>
      </button>
      <button className="mobile-row w-full text-left border-b border-border" onClick={() => run(onCommands)}>
        <Slash className="w-5 h-5 shrink-0 text-muted-foreground" />
        <span className="text-sm">{i18n('Commands')}</span>
      </button>

      {items.map((item) => (
        <button
          key={item.id}
          className="mobile-row w-full text-left border-b border-border"
          onClick={() => run(() => item.onSelect(ctx))}
        >
          <MobileIcon name={item.icon} className="w-5 h-5 shrink-0 text-muted-foreground" />
          <span className="text-sm">{i18n(item.labelKey)}</span>
        </button>
      ))}
    </MobileSheet>
  );
};
