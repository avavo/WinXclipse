import java.io.*;
import java.util.zip.*;
import java.security.*;
public final class ProtonDecodeBenchmark {
 public static void main(String[] args) throws Exception {
  if(args.length!=3)throw new IllegalArgumentException("xz nativeLib zstd");
  System.setProperty("java.library.path",args[1]);
  {
   for(String kind:new String[]{"zstd","xz"}) {
    String name=kind.equals("xz")?"org.apache.commons.compress.compressors.xz.XZCompressorInputStream":"org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream";
    InputStream source=kind.equals("xz")?new FileInputStream(args[0]):new FileInputStream(args[2]);
    long started=System.nanoTime(),count=0;MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream in=(InputStream)Class.forName(name).getConstructor(InputStream.class).newInstance(new BufferedInputStream(source,65536))) {
     byte[] buffer=new byte[65536];int size;while((size=in.read(buffer))!=-1){digest.update(buffer,0,size);count+=size;}
    }
    StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format("%02x",b&255));
    System.out.println(kind+" bytes="+count+" elapsedMs="+(System.nanoTime()-started)/1000000+" sha256="+hash);
   }
  }
 }
}
