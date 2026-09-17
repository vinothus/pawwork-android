import java.security.*; import java.security.cert.*; import java.io.*;
public class DumpCert {
  public static void main(String[] a) throws Exception {
    KeyPair kp = V1Signer.genKey("x");
    X509Certificate c = V1Signer.makeCert(kp, "PawWork");
    try (FileOutputStream f = new FileOutputStream("/tmp/cert.der")) { f.write(c.getEncoded()); }
    System.out.println("wrote /tmp/cert.der len=" + c.getEncoded().length);
  }
}
