package mes.domain.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * 수입검사기준서 - 상세 (검사항목 N건)
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tb_incoming_insp01_dtl")
public class Tb_incoming_insp01_dtl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "std_id", nullable = false)
    private Integer stdId; // 헤더 FK

    @Column(name = "seq", nullable = false)
    private Integer seq = 1; // 순번

    @Column(name = "measure_name", nullable = false, length = 200)
    private String measureName; // 검사항목

    @Column(name = "target_value", precision = 18, scale = 6)
    private BigDecimal targetValue; // 기준값

    @Column(name = "usl", precision = 18, scale = 6)
    private BigDecimal usl; // 상한규격

    @Column(name = "lsl", precision = 18, scale = 6)
    private BigDecimal lsl; // 하한규격

    @Column(name = "unit_name", length = 50)
    private String unitName; // 단위

    @Column(name = "sample_size", nullable = false)
    private Integer sampleSize = 1; // 시료수

    @Column(name = "inspect_method", length = 200)
    private String inspectMethod; // 검사방법/계측기

    @Column(name = "judge_criteria", length = 200)
    private String judgeCriteria; // 판정기준
}
