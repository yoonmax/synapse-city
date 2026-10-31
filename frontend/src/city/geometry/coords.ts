/**
 * The Java simulation uses a right-handed x/y ground plane. Three.js is y-up, so simulation y maps
 * to -z, which preserves chirality (right-hand traffic stays on the right).
 */
export const toZ = (y: number) => -y

export const palette = {
  basalt: '#0E1218',
  midnight: '#131B27',
  slate: '#243143',
  fog: '#9FAABA',
  paper: '#E6ECF2',
  cyan: '#4FD1E0',
  sodium: '#FF9A3C',
  danger: '#FF5A3C',
  asphalt: '#323B4A',
  pavement: '#3B4453',
  plinth: '#0F141B',
}
