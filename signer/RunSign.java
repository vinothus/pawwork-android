import java.security.*; import java.security.cert.*; import java.io.*;
public class RunSign {
  public static void main(String[] a) throws Exception {
    KeyPair kp = V1Signer.genKey("pawwork");
    X509Certificate c = V1Signer.makeCert(kp, "PawWork");
    try (FileOutputStream f = new FileOutputStream("/tmp/embedded-cert.der")) { f.write(c.getEncoded()); }
    byte[] sf = "test".getBytes();
    byte[] rsa = V1Signer.pkcs7SignedData(sf, c, kp.getPrivate());
    try (FileOutputStream f = new FileOutputStream("/tmp/test.rsa")) { f.write(rsa); }
    System.out.println("wrote cert + rsa, cert len " + c.getEncoded().length);
  }
}
