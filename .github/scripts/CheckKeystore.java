/*
 * Checks the release keystore before the build, so that a wrong secret is reported right away
 * with a clear message instead of failing at the end of the build.
 *
 * Reads KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD from the environment.
 * Usage (JDK 11+): java .github/scripts/CheckKeystore.java
 */
import java.io.File;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.Collections;

public class CheckKeystore {
    public static void main(String[] args) throws Exception {
        File file = new File(System.getenv("KEYSTORE_FILE"));
        char[] storePassword = System.getenv("KEYSTORE_PASSWORD").toCharArray();
        String alias = System.getenv("KEY_ALIAS");
        char[] keyPassword = System.getenv("KEY_PASSWORD").toCharArray();

        KeyStore keyStore = null;
        try {
            keyStore = KeyStore.getInstance(file, storePassword);
        } catch (Exception e) {
            fail(
                    "Impossible d'ouvrir le keystore : KEYSTORE_BASE64 ne contient pas un keystore"
                            + " valide, ou KEYSTORE_PASSWORD est incorrect ("
                            + e.getMessage()
                            + ").");
        }

        if (!keyStore.isKeyEntry(alias)) {
            fail(
                    "Aucune clé « "
                            + alias
                            + " » dans le keystore : KEY_ALIAS doit valoir l'un de ces alias : "
                            + String.join(", ", Collections.list(keyStore.aliases()))
                            + ".");
        }

        try {
            keyStore.getKey(alias, keyPassword);
        } catch (UnrecoverableKeyException e) {
            fail(
                    "KEY_PASSWORD est incorrect. Pour un keystore créé avec keytool (format"
                            + " PKCS12), c'est le même mot de passe que KEYSTORE_PASSWORD.");
        }

        System.out.println("Keystore OK (" + keyStore.getType() + ")");
    }

    private static void fail(String message) {
        System.out.println("::error::" + message);
        System.exit(1);
    }
}
