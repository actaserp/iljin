package mes.app;


import lombok.extern.slf4j.Slf4j;
import mes.app.system.service.MailSettingService;
import mes.config.Settings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.util.*;

@Slf4j
@Service
public class MailService {
    @Autowired
    private MailSettingService mailSettingService;

    /** 발송 결과: 실제로 쓴 보내는 주소 (이력에 남긴다) */
    public static class Result {
        public String fromAddr;
        public String fromName;
    }

    public void sendVerificationEmail(String to, String usernm, String uuid, String content) {
        String subject = content + " 인증 메일입니다.";
        String text = "안녕하세요, " + usernm + "님.\n\n"
                        + "다음 인증 코드를 입력하여 " + content + "을 완료하세요:\n"
                        + uuid + "\n\n"
                        + "이 코드는 3분 동안 유효합니다.";
        send(List.of(to), null, subject, text, false, null, null, null, null);
    }

    public Result sendMailWithAttachment(List<String> recipients, String subject, String body, File attachment, String attachmentFileName, String replyTo) {
        return sendMailWithAttachment(recipients, null, subject, body, attachment, attachmentFileName, replyTo, null);
    }

    public Result sendMailWithAttachment(List<String> recipients, List<String> cc, String subject, String body, File attachment, String attachmentFileName, String replyTo) {
        return sendMailWithAttachment(recipients, cc, subject, body, attachment, attachmentFileName, replyTo, null);
    }

    /**
     * @param senderUserName 보낸 담당자 이름 (보내는 이름에 붙인다, 없으면 회사명만)
     */
    public Result sendMailWithAttachment(List<String> recipients, List<String> cc, String subject, String body,
                                         File attachment, String attachmentFileName, String replyTo, String senderUserName) {
        // 본문: 화면 textarea 값이라 줄바꿈을 살려 HTML 로 보낸다
        String html = toHtml(body);
        return send(recipients, cc, subject, html, true, attachment, attachmentFileName, replyTo, senderUserName);
    }

    /** 시스템관리 > 메일 설정의 [시험 발송] — 저장 전 값으로 보낸다 */
    public void sendTest(Map<String, Object> setting, String to) {
        org.springframework.mail.javamail.JavaMailSenderImpl s = mailSettingService.senderForTest(setting);
        try {
            MimeMessage m = s.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(m, false, "UTF-8");
            String from = setting.get("from_addr") == null || setting.get("from_addr").toString().isBlank()
                            ? String.valueOf(setting.get("username")) : setting.get("from_addr").toString().trim();
            String name = setting.get("from_name") == null ? null : setting.get("from_name").toString().trim();
            h.setFrom(new InternetAddress(from, StringUtils.hasText(name) ? name : null, "UTF-8"));
            h.setTo(to);
            h.setSubject("[MES] 메일 설정 시험 발송");
            h.setText("MES 메일 설정 시험 발송입니다.\n\n서버: " + s.getHost() + ":" + s.getPort() + "\n계정: " + s.getUsername()
                        + "\n\n이 메일이 도착했으면 설정이 맞습니다.", false);
            s.send(m);
        } catch (Exception e) {
            throw new RuntimeException(friendly(e), e);
        }
    }

    // =================================================================

    private Result send(List<String> recipients, List<String> cc, String subject, String body, boolean html,
                        File attachment, String attachName, String replyTo, String senderUserName) {
        MailSettingService.Account acc = mailSettingService.companyAccount();   // 설정 없음·잠김이면 여기서 사유와 함께 막힌다

        List<String> to = clean(recipients);
        if (to.isEmpty()) throw new IllegalArgumentException("받는 사람이 없습니다.");
        List<String> ccList = clean(cc);
        ccList.removeIf(c -> to.stream().anyMatch(t -> t.equalsIgnoreCase(c)));

        String name = displayName(acc, senderUserName);
        try {
            MimeMessage message = acc.sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, attachment != null, "UTF-8");
            helper.setFrom(new InternetAddress(acc.fromAddr, StringUtils.hasText(name) ? name : null, "UTF-8"));
            helper.setTo(to.toArray(new String[0]));
            if (!ccList.isEmpty()) helper.setCc(ccList.toArray(new String[0]));
            if (StringUtils.hasText(replyTo)) {
                helper.setReplyTo(replyTo.trim());
                // 담당자에게 사본 (받는 사람·참조에 이미 있으면 넣지 않는다)
                String r = replyTo.trim();
                if (acc.bccSender && to.stream().noneMatch(r::equalsIgnoreCase) && ccList.stream().noneMatch(r::equalsIgnoreCase)
                      && !r.equalsIgnoreCase(acc.fromAddr))
                    helper.setBcc(r);
            }
            helper.setSubject(subject == null ? "" : subject);
            helper.setText(body == null ? "" : body, html);
            if (attachment != null)
                helper.addAttachment(StringUtils.hasText(attachName) ? attachName : attachment.getName(), new FileSystemResource(attachment));

            acc.sender.send(message);
            mailSettingService.markOk(acc.id);

            Result r = new Result();
            r.fromAddr = acc.fromAddr;
            r.fromName = name;
            return r;
        } catch (Exception e) {
            mailSettingService.markFail(acc.id, e);
            log.error("메일 발송 실패 (보내는 계정 {}): {}", acc.fromAddr, MailSettingService.rootMessage(e));
            throw new RuntimeException(friendly(e), e);
        }
    }

    private static String displayName(MailSettingService.Account acc, String userName) {
        String base = acc.fromName == null ? "" : acc.fromName.trim();
        String u = userName == null ? "" : userName.trim();
        if (acc.nameWithUser && !u.isEmpty()) return base.isEmpty() ? u : base + " " + u;
        return base;
    }

    /** 사용자에게 보여 줄 실패 사유 */
    static String friendly(Throwable e) {
        String raw = MailSettingService.rootMessage(e);
        if (MailSettingService.isAuthFailure(e))
            return "메일 계정 인증 실패: 아이디·비밀번호(앱 비밀번호)를 확인하세요. 연속 " + MailSettingService.LOCK_AFTER
                     + "회 실패하면 " + MailSettingService.LOCK_MINUTES + "분 동안 발송을 멈춥니다. (" + raw + ")";
        String low = raw.toLowerCase();
        if (low.contains("unknownhost") || low.contains("couldn't connect") || low.contains("connect timed out") || low.contains("connection refused"))
            return "메일 서버에 연결하지 못했습니다: 서버 주소·포트·보안 방식을 확인하세요. (" + raw + ")";
        if (low.contains("invalid addresses") || low.contains("550") || low.contains("553"))
            return "받는 주소가 거부됐습니다: 메일 주소를 확인하세요. (" + raw + ")";
        if (low.contains("552") || low.contains("size"))
            return "첨부 파일이 메일 서버 한도를 넘었습니다. (" + raw + ")";
        return "메일 발송 실패: " + raw;
    }

    private static List<String> clean(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        Set<String> seen = new HashSet<>();
        for (String s : in) {
            if (!StringUtils.hasText(s)) continue;
            String t = s.trim();
            if (seen.add(t.toLowerCase())) out.add(t);
        }
        return out;
    }

    private static String toHtml(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("<") && t.contains(">")) return text;   // 이미 HTML
        String esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<div style=\"font-size:14px;line-height:1.7\">" + esc.replace("\r\n", "\n").replace("\n", "<br>") + "</div>";
    }
}
