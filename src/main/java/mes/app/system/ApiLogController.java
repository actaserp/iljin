package mes.app.system;


import lombok.RequiredArgsConstructor;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/system/api-log")
@RequiredArgsConstructor
public class ApiLogController {

    private final SqlRunner sqlRunner;


    @GetMapping("/read")
    public AjaxResult getList(@RequestParam String srchStartDt,
                              @RequestParam String srchEndDt,
                              @RequestParam String keyword
                              ){

        MapSqlParameterSource param = new MapSqlParameterSource();


        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        LocalDateTime st = LocalDateTime.parse(srchStartDt + " 00:00:00", formatter);
        LocalDateTime ed = LocalDateTime.parse(srchEndDt + " 23:59:59", formatter);


        param.addValue("st", st);
        param.addValue("en" , ed);
        param.addValue("endpoint" , "%" + keyword + "%");


        // api_log_entry 는 호출 한 건씩 쌓인다 (occurrence_time, method, api_address, duration_second).
        // 예전 조회는 없는 집계 칸(endpoint · avg_call_cnt · log_timestamp)을 찾아 늘 DB 오류 → 빈 화면이었음.
        // 화면 칸에 맞춰 주소별로 집계한다 (2026-11-26).
        String sql = """
                    select concat(method, ' ', api_address) as endpoint
                         , round(cast(avg(duration_second) * 1000 as numeric), 1) as avg_response_time_ms
                         , round(cast(max(duration_second) * 1000 as numeric), 1) as max_response_time_ms
                         , round(cast(min(duration_second) * 1000 as numeric), 1) as min_response_time_ms
                         , round(cast(count(*) as numeric) / greatest(count(distinct cast(occurrence_time as date)), 1), 1) as avg_call_cnt
                         , count(*) as call_cnt
                    from api_log_entry
                    where occurrence_time between :st and :en
                """;
        if(!keyword.isEmpty())
        {
            sql += """
                and api_address like :endpoint
                """;
        }
        sql += """
                group by method, api_address
                order by avg_call_cnt desc
                """;
        AjaxResult result = new AjaxResult();

        result.data = sqlRunner.getRows(sql, param);

        return result;
    }
}
