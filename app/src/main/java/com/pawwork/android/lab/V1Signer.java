package com.pawwork.android.lab;
// Pure Java-SE APK v1 signer (no Android deps) — same code runs on host (javac)
// and on-device. Generates a self-signed cert, signs the APK with the v1/JAR
// scheme (META-INF/MANIFEST.MF + CERT.SF + CERT.RSA PKCS#7), so PackageInstaller
// accepts it. Validated on host with: apksigner verify --verbose out.apk
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.*;
import java.util.*;
import java.util.zip.*;
import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public class V1Signer {

    // ---------------------------- minimal DER encoder ----------------------------
    static byte[] derLen(long len) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (len < 0x80) b.write((int) len);
        else {
            int bytes = (Long.SIZE - Long.numberOfLeadingZeros(len) + 7) / 8;
            b.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) b.write((int) ((len >> (i * 8)) & 0xFF));
        }
        return b.toByteArray();
    }

    static byte[] derTag(byte tag, byte[] content) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(tag);
        b.write(derLen(content.length));
        b.write(content);
        return b.toByteArray();
    }

    // TLV of the given construct: SEQ/SET are the caller's job
    static byte[] seq(byte[]... items) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] it : items) b.write(it);
        return derTag((byte) 0x30, b.toByteArray());
    }

    static byte[] setOf(byte[]... items) throws IOException {
        // contents must be sorted by full DER encoding for SET (OF)
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] it : items) b.write(it);
        return derTag((byte) 0x31, b.toByteArray());
    }

    static byte[] oid(String dotted) throws IOException {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int first = Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]);
        body.write(first);
        for (int i = 2; i < parts.length; i++) {
            long v = Long.parseLong(parts[i]);
            byte[] tmp = new byte[9];
            int n = 0;
            tmp[n++] = (byte) (v & 0x7F);
            while ((v >>= 7) != 0) tmp[n++] = (byte) ((v & 0x7F) | 0x80);
            for (int j = n - 1; j >= 0; j--) body.write(tmp[j]);
        }
        return derTag((byte) 0x06, body.toByteArray());
    }

    static byte[] integer(long v) throws IOException {
        int bytes = 1;
        while (bytes < 8 && (v >> (bytes * 8)) != 0 && (v >> (bytes * 8)) != -1) bytes++;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write((int) ((v >> ((bytes - 1) * 8)) & 0xFF));
        for (int i = bytes - 2; i >= 0; i--) b.write((int) ((v >> (i * 8)) & 0xFF));
        return derTag((byte) 0x02, b.toByteArray());
    }

    static byte[] integerByte(byte[] bigEndian) throws IOException {
        return derTag((byte) 0x02, bigEndian);
    }

    static byte[] bitString(byte[] value) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0); // unused bits
        b.write(value);
        return derTag((byte) 0x03, b.toByteArray());
    }

    static byte[] octetString(byte[] value) throws IOException {
        return derTag((byte) 0x04, value);
    }

    static byte[] utf8String(String s) throws IOException {
        return derTag((byte) 0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] null_() {
        return new byte[] { 0x05, 0x00 };
    }

    static byte[] context0(byte[] value) throws IOException { // [0] EXPLICIT
        return derTag((byte) 0xA0, value);
    }

    static byte[] context1(byte[] value) throws IOException { // [1] EXPLICIT
        return derTag((byte) 0xA1, value);
    }

    static byte[] context2(byte[] value) throws IOException { // [2] EXPLICIT (for issuer serial? not needed here)
        return derTag((byte) 0xA2, value);
    }

    static byte[] algorithmIdentifier(String oid, byte[] params) throws IOException {
        return seq(oid(oid), params == null ? null_() : params);
    }

    // ---------------------------- X.509 self-signed ----------------------------
    static X509Certificate makeCert(KeyPair kp, String subject) throws Exception {
        // Validity
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(2025, Calendar.JANUARY, 1, 0, 0, 0);
        long from = cal.getTimeInMillis() / 1000;
        cal.set(2055, Calendar.JANUARY, 1, 0, 0, 0);
        long to = cal.getTimeInMillis() / 1000;

        BigInteger serial = new BigInteger(64, new SecureRandom()).abs();

        byte[] tbs = seq(
            context0(integer(2)),                                  // version v3 [0] EXPLICIT
            integerByte(serial.toByteArray()),                     // serial
            algorithmIdentifier("1.2.840.113549.1.1.11", null),            // sha256WithRSA
            name(new X500Principal("CN=" + subject).getEncoded()),         // issuer
            seq(                                                           // validity
                derTag((byte) 0x17, derUtcTime(from)),                     // notBefore UTCTime (DER: must be UTCTime <2050)
                derTag((byte) 0x18, derGeneralizedTime(to))),              // notAfter  GeneralizedTime
            name(new X500Principal("CN=" + subject).getEncoded()),         // subject
            seq(                                                           // subjectPublicKeyInfo
                algorithmIdentifier("1.2.840.113549.1.1.1", null),         // rsaEncryption
                bitString(seq(                                             // RSAPublicKey
                    integerByte(((java.security.interfaces.RSAPublicKey) kp.getPublic()).getModulus().toByteArray()),
                    integer(((java.security.interfaces.RSAPublicKey) kp.getPublic()).getPublicExponent().longValue())))),
            context3(seq(seq(oid("2.5.29.19"), octetString(seq())))));   // extensions: basicConstraints cA=FALSE

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] sigBytes = sig.sign();

        byte[] certDer = seq(
            tbs,
            algorithmIdentifier("1.2.840.113549.1.1.11", null),
            bitString(sigBytes));

        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
    }

    // [3] EXPLICIT for extensions
    static byte[] context3(byte[] value) throws IOException {
        return derTag((byte) 0xA3, value);
    }

    static byte[] name(byte[] x500Name) throws IOException {
        return x500Name; // already DER SEQUENCE
    }

    static byte[] derUtcTime(long epochSeconds) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(epochSeconds * 1000L)).getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] derGeneralizedTime(long epochSeconds) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMddHHmmss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(epochSeconds * 1000L)).getBytes(StandardCharsets.US_ASCII);
    }

    // ---------------------------- PKCS#7 SignedData ---------------------------
    static byte[] pkcs7SignedData(byte[] contentBytes, X509Certificate cert, PrivateKey privateKey) throws Exception {
        // content = CERT.SF bytes
        byte[] issuerName = cert.getIssuerX500Principal().getEncoded();
        BigInteger serial = cert.getSerialNumber();

        byte[] messageDigest = MessageDigest.getInstance("SHA-256").digest(contentBytes);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(contentBytes);
        byte[] encryptedDigest = signature.sign();
        byte[] certEncoded = cert.getEncoded();

        byte[] signerInfo = seq(
            integer(1),                                                              // version
            seq(                                                                     // issuerAndSerialNumber
                issuerName,
                integerByte(serial.toByteArray())),
            algorithmIdentifier("2.16.840.1.101.3.4.2.1", null),                     // digestAlgorithm = sha256
            algorithmIdentifier("1.2.840.113549.1.1.1", null),                       // digestEncryptionAlgorithm = rsaEncryption
            octetString(encryptedDigest));                                       // encryptedDigest (untagged in PKCS#7)

        byte[] sd = seq(
            integer(1),                                                              // version
            setOf(algorithmIdentifier("2.16.840.1.101.3.4.2.1", null)),              // digestAlgorithms {sha256}
            seq(oid("1.2.840.113549.1.7.1")),                                        // contentInfo: pkcs7-data, DETACHED (no eContent)
            context0(certEncoded),                                              // certificates [0] → cert directly (matches apksigner layout)
            setOf(signerInfo));                                                      // signerInfos

        return seq(oid("1.2.840.113549.1.7.2"), context0(sd));                       // SignedData
    }

    // ---------------------------- main: sign an APK -----------------------------
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: V1Signer <input.apk> <output.apk> [alias]"); System.exit(2); }
        File in = new File(args[0]);
        File out = new File(args[1]);
        KeyPair kp = genKey(args.length > 2 ? args[2] : "pawwork");
        signApk(in, out, kp);
    }

    /** Sign an APK with the v1 (JAR) scheme using the given key pair. */
    public static void signApk(File in, File out, KeyPair kp) throws Exception {

        // 1) read all entries sorted (manifest order matters for digest stability)
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipFile zf = new ZipFile(in)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                entries.put(e.getName(), readAll(zf.getInputStream(e)));
            }
        }

        // 2) MANIFEST.MF
        StringBuilder manifest = new StringBuilder("Manifest-Version: 1.0\r\nCreated-By: 1.0 (PawWork)\r\n\r\n");
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(e.getValue());
            manifest.append("Name: ").append(e.getKey()).append("\r\n");
            manifest.append("SHA-256-Digest: ").append(Base64.getEncoder().encodeToString(digest)).append("\r\n\r\n");
        }

        // 3) CERT.SF — digest of manifest, one section per entry
        byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        byte[] manifestDigest = MessageDigest.getInstance("SHA-256").digest(manifestBytes);
        StringBuilder sf = new StringBuilder();
        sf.append("Signature-Version: 1.0\r\n");
        sf.append("Created-By: 1.0 (PawWork)\r\n");
        sf.append("SHA-256-Digest-Manifest: ").append(Base64.getEncoder().encodeToString(manifestDigest)).append("\r\n\r\n");
        int pos = 0;
        String ms = manifest.toString();
        while (pos < ms.length()) {
            int sectionStart = pos;
            int sectionEnd = ms.indexOf("\r\n\r\n", pos);
            if (sectionEnd < 0) break;
            String section = ms.substring(sectionStart, sectionEnd + 2); // include blank line + 2 CRLF? keep simple: up to \r\n
            pos = sectionEnd + 4;
            String nameLine = section.split("\r\n")[0];
            if (!nameLine.startsWith("Name: ")) continue;
            byte[] secBytes = section.getBytes(StandardCharsets.UTF_8);
            byte[] secDigest = MessageDigest.getInstance("SHA-256").digest(secBytes);
            sf.append("Name: ").append(nameLine.substring(6)).append("\r\n");
            sf.append("SHA-256-Digest: ").append(Base64.getEncoder().encodeToString(secDigest)).append("\r\n\r\n");
        }

        // 4) CERT.RSA (PKCS#7)
        X509Certificate cert = makeCert(kp, "PawWork");
        byte[] sfBytes = sf.toString().getBytes(StandardCharsets.UTF_8);
        byte[] rsa = pkcs7SignedData(sfBytes, cert, kp.getPrivate());

        // 5) write output zip with all entries + META-INF files first
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            put(zos, "META-INF/MANIFEST.MF", manifestBytes);
            put(zos, "META-INF/CERT.SF", sfBytes);
            put(zos, "META-INF/CERT.RSA", rsa);
            for (Map.Entry<String, byte[]> e : entries.entrySet()) put(zos, e.getKey(), e.getValue());
        }
        System.out.println("signed " + out + " (" + out.length() + " bytes)");
    }

    static KeyPair genKey(String alias) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}