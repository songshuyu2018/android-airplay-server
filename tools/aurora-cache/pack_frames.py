#!/usr/bin/env python3
"""Pack unpremultiplied band{0,1}-NNNN.png frames as bounded, seekable AUR3 assets."""
import argparse,concurrent.futures,hashlib,json,pathlib,struct,subprocess,tempfile,zlib
parser=argparse.ArgumentParser();parser.add_argument('frames',type=pathlib.Path);parser.add_argument('output',type=pathlib.Path);parser.add_argument('--encoder',required=True,type=pathlib.Path);parser.add_argument('--period',type=float,default=8/.26);args=parser.parse_args();args.encoder=args.encoder.expanduser().resolve();args.output.mkdir(parents=True,exist_ok=True)
frames=[sorted(args.frames.glob(f'band{band}-*.png')) for band in range(2)];count=len(frames[0]);assert 4<=count<=2400 and len(frames[1])==count
width,height=struct.unpack('>II',frames[0][0].read_bytes()[16:24]);assert width==height*3
size=((width+3)//4)*((height+3)//4)*16;assert size<=1048576
with tempfile.TemporaryDirectory() as temp:
 temp=pathlib.Path(temp)
 def encode(p):
  out=temp/(p.stem+'.astc');subprocess.run([str(args.encoder),'-cl',str(p),str(out),'4x4','-medium','-j','2'],check=True,stdout=subprocess.DEVNULL);return out
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(encode,frames[0]+frames[1]))
 for band in range(2):
  split=(count+1)//2;offsets=[40+(count+1)*8]
  for part,(lo,hi) in enumerate([(0,split),(split,count)]):
   with (args.output/f'aurora_band_{band}_{part}.aurora').open('wb') as out:
    for i in range(lo,hi):
     assert frames[band][i].stem==f'band{band}-{i:04d}'
     raw=(temp/(frames[band][i].stem+'.astc')).read_bytes();assert len(raw)==size+16
     encoded=zlib.compress(raw[16:],6);out.write(encoded);offsets.append(offsets[-1]+len(encoded))
  with (args.output/f'aurora_band_{band}.aurora').open('wb') as out:
   out.write(struct.pack('>8IfI',0x41555232,3,width,height,count,4,4,size,args.period,256));out.write(struct.pack('>'+str(count+1)+'Q',*offsets))
manifest=[{'file':p.name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(args.output.glob('*.aurora'))];(args.output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(manifest,indent=2))
