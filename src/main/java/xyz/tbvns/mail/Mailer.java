package xyz.tbvns.mail;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.Properties;

public class Mailer {

    public static void sendMail(String to, String subject, String body) throws MessagingException {
        String host = System.getenv("email_server");
        String username = System.getenv("email_adress");
        String password = System.getenv("email_password");

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", host);

        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.port", "587");

        props.put("mail.smtp.ssl.trust", "mail.tbvns.xyz");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(username));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);
        message.setContent(body, "text/html; charset=utf-8");

        Transport.send(message);
    }
}