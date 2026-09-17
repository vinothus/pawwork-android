import java.io.*; import java.nio.file.*;
import com.android.apksig.internal.util.X509CertificateUtils;
public class CertTest2 {
  public static void main(String[] a) throws Exception {
    byte[] cert = Files.readAllBytes(Paths.get("/tmp/b/apk-cert.der"));
    try {
      var c = X509CertificateUtils.generateCertificate(cert);
      System.out.println("APK-CERT OK: " + c.getSubjectX500Principal());
    } catch (Exception e) { System.out.println("APK-CERT FAIL: " + e); }
  }
}
