// SVG simgesinden Windows için çok boyutlu ICO ve önizleme PNG dosyası üretir.
// Kullanım: node build-icon.cjs <sharp paketinin bulunduğu node_modules klasörü>
const fs=require('fs'),path=require('path');
const sharp=require(require.resolve('sharp',{paths:[process.argv[2]||process.cwd()]}));
(async()=>{
  const dir=path.join(__dirname,'assets');
  const svg=fs.readFileSync(path.join(dir,'rj45.svg'));
  const sizes=[16,24,32,48,64,128,256];
  const images=await Promise.all(sizes.map(size=>sharp(svg).resize(size,size).png().toBuffer()));
  const header=Buffer.alloc(6+16*sizes.length);header.writeUInt16LE(1,2);header.writeUInt16LE(sizes.length,4);
  let offset=header.length;
  images.forEach((image,i)=>{const p=6+i*16;header[p]=header[p+1]=sizes[i]===256?0:sizes[i];header.writeUInt16LE(1,p+4);header.writeUInt16LE(32,p+6);header.writeUInt32LE(image.length,p+8);header.writeUInt32LE(offset,p+12);offset+=image.length;});
  fs.writeFileSync(path.join(dir,'rj45.ico'),Buffer.concat([header,...images]));
  await sharp(svg).resize(512,512).png().toFile(path.join(dir,'rj45-preview.png'));
  console.log('RJ45: ICO (16–256 piksel), SVG ve PNG hazır.');
})().catch(e=>{console.error(e);process.exitCode=1});
