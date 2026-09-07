package mes.app.pda.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@Slf4j
public class PdaHomeController {

    @Autowired
    private ObjectMapper jacksonObjectMapper;

    /**
     * PDA 배포 파일이 놓이는 폴더. version.json 과 app_*.apk 가 <b>같은 폴더</b>에 있다
     * (다른 프로젝트들과 같은 관례). 예전에는 version.json 은 iljin/, apk 는 iljinpda/ 로
     * 갈라져 있어 한쪽만 채우면 조용히 실패했다.
     *
     * 서버마다 경로가 다를 수 있어 설정으로 뺀다.
     * application.properties 에 pda.file-root 를 두면 그 값이 우선한다.
     */
    @Value("${pda.file-root:C:/Temp/mes21/iljinpda}")
    private String pdaFileRoot;

    @GetMapping("/pda/app/version")
    public AjaxResult getVersion() {
        AjaxResult result = new AjaxResult();

        File file = new File(pdaFileRoot, "version.json");
        if(!file.exists()){
            result.success = false;
            result.message = "버전파일이 존재하지 않습니다.";
            return result;
        }

        try (FileInputStream fis = new FileInputStream(file)){

            Map<String, Object> jsonMap = jacksonObjectMapper.readValue(fis, Map.class);
            String version = jsonMap.get("version").toString();

            result.success = true;
            result.data = version;
        }catch (Exception e){
            result.success = false;
            result.message = "버전파일을 읽는중 에러가 발생하였습니다.";
            log.error("endpoint : /pda/app/version , content= {}" , e.getMessage());
        }

        return result;
    }

    @GetMapping("/pda/app/version/iljinpda_latest.apk")
    public ResponseEntity<Resource> downloadApk(@RequestParam String version){
        try{
            // version.json 과 같은 폴더에서 찾는다
            File file = new File(pdaFileRoot, "app_" + version + ".apk");

            if(!file.exists()){
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(null);
            }

            Resource resource = new FileSystemResource(file);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(file.getName(), StandardCharsets.UTF_8)
                    .build()
            );
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity.ok()
                    .headers(headers)
                    .contentLength(file.length())
                    .body(resource);
        }catch (Exception e){
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
    }

}
