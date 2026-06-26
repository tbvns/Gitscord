package xyz.tbvns.mail;

import jakarta.mail.MessagingException;
import org.apache.commons.lang3.RandomStringUtils;

import java.util.HashMap;

public class MailVerification {
    public static final HashMap<String, String> codes = new HashMap();

    public static String getCode(String mail) {
        String code = RandomStringUtils.randomNumeric(6);
        codes.put(mail, code);
        return code;
    }

    public static boolean verify(String mail, String code) {
        return codes.get(mail).equals(code);
    }

    public static void sendMail(String mail) throws MessagingException {
        Mailer.sendMail(mail, "Gitscord Email Verification", "<p>Your verification code is: <b>" + MailVerification.getCode(mail) + "</b>.</p>");
    }
}
