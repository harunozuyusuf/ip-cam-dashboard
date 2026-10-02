// RJ45 SVG simgesini macOS için PNG temelli ICNS boyutlarına dönüştürür.
const fs=require('fs'),path=require('path');
const sharp=require(require.resolve('sharp',{paths:[process.argv[2]||process.cwd()]}));
(async()=>{
  const svg=fs.readFileSync(path.join(__dirname,'../installer/assets/rj45.svg'));
  const entries=[['icp4',16],['icp5',32],['icp6',64],['ic07',128],['ic08',256],['ic09',512],['ic10',1024]];
  const chunks=[];
  for(const [type,size] of entries){
    const png=await sharp(svg).resize(size,size).png().toBuffer();
    const header=Buffer.alloc(8);header.write(type);header.writeUInt32BE(png.length+8,4);
    chunks.push(header,png);
  }
  const header=Buffer.alloc(8);header.write('icns');header.writeUInt32BE(8+chunks.reduce((n,b)=>n+b.length,0),4);
  fs.writeFileSync(path.join(__dirname,'rj45.icns'),Buffer.concat([header,...chunks]));
  console.log('macOS RJ45 simgesi hazır.');
})().catch(e=>{console.error(e);process.exitCode=1});
