import { copyFile, mkdir } from 'node:fs/promises';
const base=new URL('../backend/target/',import.meta.url);
await mkdir(new URL('release/',base),{recursive:true});
await copyFile(new URL('xim-1.0-SNAPSHOT.jar',base),new URL('release/xim.jar',base));
