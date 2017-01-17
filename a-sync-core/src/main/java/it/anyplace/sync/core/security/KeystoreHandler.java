/* 
 * Copyright (C) 2016 Davide Imbriaco
 *
 * This Java file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package it.anyplace.sync.core.security;

import it.anyplace.sync.core.interfaces.RelayConnection;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import static com.google.common.base.Objects.equal;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import it.anyplace.sync.core.configuration.ConfigurationService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Date;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v1CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v1CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import java.security.cert.CertPath;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.commons.lang3.tuple.Pair;

/**
 *
 * @author aleph
 */
public class KeystoreHandler {

    private final static String JKS_PASSWORD = "password",
        KEY_PASSWORD = "password",
        KEY_ALGO = "RSA",
        SIGNATURE_ALGO = "SHA1withRSA",
        CERTIFICATE_CN = "CN=syncthing",
        BC_PROVIDER = "BC",
        TLS_VERSION = "TLSv1.2";
    public final static String CONFIGURATION_KEYSTORE_PROP = "keystore",
        CONFIGURATION_DEVICEID_PROP = "deviceid";
    private final static int KEY_SIZE = 3072, SOCKET_TIMEOUT = 2000;

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ConfigurationService configuration;
    private final KeyStore keyStore;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private KeystoreHandler(ConfigurationService configuration, KeyStore keyStore) {
        checkNotNull(configuration);
        checkNotNull(keyStore);
        this.configuration = configuration;
        this.keyStore = keyStore;
    }

    private static String derToPem(byte[] der) {
        return "-----BEGIN CERTIFICATE-----\n" + BaseEncoding.base64().withSeparator("\n", 76).encode(der) + "\n-----END CERTIFICATE-----";
    }

    private byte[] exportKeystoreToData() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            keyStore.store(out, JKS_PASSWORD.toCharArray());
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException ex) {
            throw new RuntimeException(ex);
        }
        return out.toByteArray();
    }

    public static String derDataToDeviceIdString(byte[] certificateDerData) {
        return hashDataToDeviceIdString(Hashing.sha256().hashBytes(certificateDerData).asBytes());
    }

    public static String hashDataToDeviceIdString(byte[] hashData) {
        checkArgument(hashData.length == Hashing.sha256().bits() / 8);
        String string = BaseEncoding.base32().encode(hashData).replaceAll("=+$", "");
        string = Joiner.on("").join(Iterables.transform(Splitter.fixedLength(13).split(string), new Function<String, String>() {
            @Override
            public String apply(String part) {
                return part + generateLuhn32Checksum(part);
            }
        }));
        return Joiner.on("-").join(Splitter.fixedLength(7).split(string));
    }

    public static byte[] deviceIdStringToHashData(String deviceId) {
        checkArgument(deviceId.matches("^[A-Z0-9]{7}-[A-Z0-9]{7}-[A-Z0-9]{7}-[A-Z0-9]{7}-[A-Z0-9]{7}-[A-Z0-9]{7}-[A-Z0-9]{7}-[A-Z0-9]{7}$"), "device id syntax error for deviceId = %s", deviceId);
        String base32data = deviceId.replaceFirst("(.{7})-(.{6}).-(.{7})-(.{6}).-(.{7})-(.{6}).-(.{7})-(.{6}).", "$1$2$3$4$5$6$7$8") + "===";
        byte[] binaryData = BaseEncoding.base32().decode(base32data);
        checkArgument(binaryData.length == Hashing.sha256().bits() / 8);
        return binaryData;
    }

    public static void validateDeviceId(String peer) {
        checkArgument(equal(hashDataToDeviceIdString(deviceIdStringToHashData(peer)), peer));
    }

    public KeyManager[] getKeyManagers() throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, KEY_PASSWORD.toCharArray());
        return keyManagerFactory.getKeyManagers();
    }

    // TODO serialize keystore
    private static char generateLuhn32Checksum(String string) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int factor = 1;
        int sum = 0;
        int n = alphabet.length();
        for (char character : string.toCharArray()) {
            int index = alphabet.indexOf(character);
            checkArgument(index >= 0);
            int add = factor * index;
            factor = factor == 2 ? 1 : 2;
            add = (add / n) + (add % n);
            sum += add;
        }
        int remainder = sum % n;
        int check = (n - remainder) % n;
        return alphabet.charAt(check);
    }

    private SSLSocketFactory getSocketFactory() throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException {
        SSLContext sslContext = SSLContext.getInstance(TLS_VERSION);
        sslContext.init(getKeyManagers(), new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] xcs, String string) throws CertificateException {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] xcs, String string) throws CertificateException {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }
        }}, null);
        return sslContext.getSocketFactory();
    }

    public Socket wrapSocket(Socket socket, boolean isServerSocket, final String... protocols) throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException, IOException {
        logger.debug("wrapping plain socket, server mode = {}", isServerSocket);
        SSLSocket sslSocket = (SSLSocket) getSocketFactory().createSocket(socket, null, socket.getPort(), true);
        if (isServerSocket) {
            sslSocket.setUseClientMode(false);
        }
        enableALPN(sslSocket, protocols);
        return sslSocket;
    }

    public final static String BEP = "bep/1.0", RELAY = "bep-relay";

    public Socket createSocket(InetSocketAddress relaySocketAddress, final String... protocols) throws Exception {
        SSLSocket socket = (SSLSocket) getSocketFactory().createSocket();
        socket.connect(relaySocketAddress, SOCKET_TIMEOUT);
        enableALPN(socket, protocols);
        return socket;
    }

    private void enableALPN(final SSLSocket socket, final String... protocols) {
        try {
            Class.forName("org.eclipse.jetty.alpn.ALPN");
            org.eclipse.jetty.alpn.ALPN.put(socket, new org.eclipse.jetty.alpn.ALPN.ClientProvider() {

                @Override
                public List<String> protocols() {
                    return Arrays.asList(protocols);
                }

                @Override
                public void unsupported() {
                    org.eclipse.jetty.alpn.ALPN.remove(socket);
                }

                @Override
                public void selected(String protocol) {
                    org.eclipse.jetty.alpn.ALPN.remove(socket);
                    logger.debug("ALPN select protocol = {}", protocol);
                }
            });
        } catch (ClassNotFoundException | NoClassDefFoundError cne) {
            logger.warn("ALPN not available, org.eclipse.jetty.alpn.ALPN not found! ( requires java -Xbootclasspath/p:path/to/alpn-boot.jar )");
        }
    }

    public void checkSocketCerificate(SSLSocket socket, String deviceId) throws SSLPeerUnverifiedException, CertificateException {
        SSLSession session = socket.getSession();
        List<Certificate> certs = Arrays.asList(session.getPeerCertificates());
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        CertPath certPath = certificateFactory.generateCertPath(certs);
        Certificate certificate = certPath.getCertificates().get(0);
        checkArgument(certificate instanceof X509Certificate);
        byte[] derData = certificate.getEncoded();
        String deviceIdFromCertificate = derDataToDeviceIdString(derData);
        logger.trace("remote pem certificate =\n{}", derToPem(derData));
        checkArgument(equal(deviceIdFromCertificate, deviceId), "device id mismatch! expected = %s, got = %s", deviceId, deviceIdFromCertificate);
        logger.debug("remote ssl certificate match deviceId = {}", deviceId);
    }

    public Socket wrapSocket(RelayConnection relayConnection, String... protocols) throws Exception {
        return wrapSocket(relayConnection.getSocket(), relayConnection.isServerSocket(), protocols);
    }

    public static Loader newLoader() {
        return new Loader();
    }

    public static class Loader {

        private final Logger logger = LoggerFactory.getLogger(getClass());
        private final static Cache<String, KeystoreHandler> keystoreHandlersCacheByHash = CacheBuilder.newBuilder()
            .maximumSize(10)
            .build();

        private Loader() {

        }

        public KeystoreHandler loadAndStore(ConfigurationService configuration) {
            synchronized (keystoreHandlersCacheByHash) {
                boolean isNew = false;
                byte[] keystoreData = configuration.getKeystore();
                if (keystoreData != null) {
                    KeystoreHandler keystoreHandlerFromCache = keystoreHandlersCacheByHash.getIfPresent(BaseEncoding.base32().encode(Hashing.sha256().hashBytes(keystoreData).asBytes()));
                    if (keystoreHandlerFromCache != null) {
                        return keystoreHandlerFromCache;
                    }
                }
                String keystoreAlgo = configuration.getKeystoreAlgo();
                if (isBlank(keystoreAlgo)) {
                    keystoreAlgo = KeyStore.getDefaultType();
                    checkNotNull(keystoreAlgo);
                    logger.debug("keystore algo set to {}", keystoreAlgo);
                    configuration.edit().setKeystoreAlgo(keystoreAlgo);
                }
                Pair<KeyStore, String> keyStore = null;
                if (keystoreData != null) {
                    try {
                        keyStore = importKeystore(keystoreData, configuration);
                    } catch (Exception ex) {
                        logger.error("error importing keystore", ex);
                    }
                }
                if (keyStore == null) {
                    try {
                        keyStore = generateKeystore(configuration);
                        isNew = true;
                    } catch (Exception ex) {
                        logger.error("error generating keystore", ex);
                    }
                }
                checkNotNull(keyStore, "unable to aquire keystore");
                KeystoreHandler keystoreHandler = new KeystoreHandler(configuration, keyStore.getLeft());
                if (isNew) {
                    configuration.edit()
                        .setDeviceId(keyStore.getRight())
                        .setKeystore(keystoreData = keystoreHandler.exportKeystoreToData())
                        .setKeystoreAlgo(keystoreAlgo)
                        .persistLater();
                }
                keystoreHandlersCacheByHash.put(BaseEncoding.base32().encode(Hashing.sha256().hashBytes(keystoreData).asBytes()), keystoreHandler);
                logger.info("keystore ready, device id = {}", configuration.getDeviceId());
                return keystoreHandler;
            }
        }

        private Pair<KeyStore, String> generateKeystore(ConfigurationService configuration) throws Exception {
            logger.debug("generating key");
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGO, BouncyCastleProvider.PROVIDER_NAME);
            keyPairGenerator.initialize(KEY_SIZE);
            KeyPair keyPair = keyPairGenerator.genKeyPair();

            ContentSigner contentSigner = new JcaContentSignerBuilder(SIGNATURE_ALGO).setProvider(BC_PROVIDER).build(keyPair.getPrivate());

            Date startDate = new Date(System.currentTimeMillis() - (24 * 60 * 60 * 1000));
            Date endDate = new Date(System.currentTimeMillis() + (10 * 365 * 24 * 60 * 60 * 1000));

            X509v1CertificateBuilder certificateBuilder = new JcaX509v1CertificateBuilder(new X500Principal(CERTIFICATE_CN), BigInteger.ZERO, startDate, endDate, new X500Principal(CERTIFICATE_CN), keyPair.getPublic());

            X509CertificateHolder certificateHolder = certificateBuilder.build(contentSigner);

            byte[] certificateDerData = certificateHolder.getEncoded();
            logger.info("generated cert =\n{}", derToPem(certificateDerData));
            String deviceId = derDataToDeviceIdString(certificateDerData);
            logger.info("device id from cert = {}", deviceId);

            KeyStore keyStore = KeyStore.getInstance(configuration.getKeystoreAlgo());
            keyStore.load(null, null);
            Certificate[] certChain = new Certificate[1];
            certChain[0] = new JcaX509CertificateConverter().setProvider(BC_PROVIDER).getCertificate(certificateHolder);
            keyStore.setKeyEntry("key", keyPair.getPrivate(), KEY_PASSWORD.toCharArray(), certChain);
            return Pair.of(keyStore, deviceId);
        }

        private Pair<KeyStore, String> importKeystore(byte[] keystoreData, ConfigurationService configuration) throws Exception {
            String keystoreAlgo = configuration.getKeystoreAlgo();
            KeyStore keyStore = KeyStore.getInstance(keystoreAlgo);
            keyStore.load(new ByteArrayInputStream(keystoreData), JKS_PASSWORD.toCharArray());
            String alias = keyStore.aliases().nextElement();
            Certificate certificate = keyStore.getCertificate(alias);
            checkArgument(certificate instanceof X509Certificate);
            byte[] derData = certificate.getEncoded();
            String deviceId = derDataToDeviceIdString(derData);
            logger.debug("loaded device id from cert = {}", deviceId);
            return Pair.of(keyStore, deviceId);
        }
    }

}
