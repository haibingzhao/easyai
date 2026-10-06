import { i18n } from './i18n';

const MAX_INPUT_BYTES = 10 * 1024 * 1024;
/** An avatar renders at 56px at most; 256 keeps a circle crisp on a 4x display without storing a photo. */
const OUTPUT_EDGE = 256;
const ACCEPTED_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];

/**
 * Centre-crop a picked picture to a square and re-encode it as a 256x256 PNG.
 *
 * Resizing here is what lets the server keep a 1 MB cap: a phone photo would otherwise be rejected on
 * every try. PNG keeps transparency; an animated GIF decodes to its first frame, which is all a circle shows.
 * EXIF orientation is applied by the browser's own image decoding, so no second code path is needed.
 */
export async function normalizeAvatarImage(file: File): Promise<File> {
  if (!ACCEPTED_TYPES.includes(file.type)) {
    throw new Error(i18n('Choose a PNG, JPEG, WebP or GIF image'));
  }
  if (file.size > MAX_INPUT_BYTES) {
    throw new Error(i18n('Choose an image smaller than 10 MB'));
  }

  const url = URL.createObjectURL(file);
  try {
    const image = await loadImage(url);
    const edge = Math.min(image.naturalWidth, image.naturalHeight);
    if (edge === 0) throw new Error(i18n('This image could not be read'));

    const canvas = document.createElement('canvas');
    canvas.width = OUTPUT_EDGE;
    canvas.height = OUTPUT_EDGE;
    const context = canvas.getContext('2d');
    if (!context) throw new Error(i18n('This image could not be read'));
    context.drawImage(
      image,
      (image.naturalWidth - edge) / 2,
      (image.naturalHeight - edge) / 2,
      edge,
      edge,
      0,
      0,
      OUTPUT_EDGE,
      OUTPUT_EDGE,
    );

    const encoded = await toPngBlob(canvas);
    if (!encoded) throw new Error(i18n('This image could not be read'));
    return new File([encoded], 'avatar.png', { type: 'image/png' });
  } finally {
    URL.revokeObjectURL(url);
  }
}

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error(i18n('This image could not be read')));
    image.src = url;
  });
}

function toPngBlob(canvas: HTMLCanvasElement): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
}
