package mes.domain.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tb_spc_std01")
public class Tb_spc_std01 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id; // 순번

    @Column(name = "item_code", nullable = false, length = 50)
    private String itemCode; // 품목코드

    @Column(name = "item_name", length = 200)
    private String itemName; // 품목명

    @Column(name = "process_code", nullable = false, length = 50)
    private String processCode; // 공정코드

    @Column(name = "process_name", length = 200)
    private String processName; // 공정명

    @Column(name = "measure_code", nullable = false, length = 50)
    private String measureCode; // 측정항목코드

    @Column(name = "measure_name", length = 200)
    private String measureName; // 측정항목명

    @Column(name = "unit_code", length = 30)
    private String unitCode; // 측정단위코드

    @Column(name = "unit_name", length = 50)
    private String unitName; // 측정단위명

    @Column(name = "target_value", precision = 18, scale = 6)
    private BigDecimal targetValue; // 타겟값

    @Column(name = "usl", precision = 18, scale = 6)
    private BigDecimal usl; // USL(상한규격)

    @Column(name = "lsl", precision = 18, scale = 6)
    private BigDecimal lsl; // LSL(하한규격)

    @Column(name = "ucl", precision = 18, scale = 6)
    private BigDecimal ucl; // UCL(상한관리한계)

    @Column(name = "lcl", precision = 18, scale = 6)
    private BigDecimal lcl; // LCL(하한관리한계)

    @Column(name = "sample_size", nullable = false)
    private Integer sampleSize = 1; // 샘플수

    @Column(name = "measure_cycle_value", nullable = false)
    private Integer measureCycleValue = 1; // 측정주기값

    @Column(name = "measure_cycle_unit", nullable = false, length = 20)
    private String measureCycleUnit = "MIN"; // 측정주기단위

    @Column(name = "use_yn", nullable = false, length = 1)
    private String useYn = "Y"; // 사용여부

    @Column(name = "remark")
    private String remark; // 비고

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ DEFAULT now()", nullable = false)
    private OffsetDateTime createdAt; // 등록일시

    @Column(name = "created_by", length = 50)
    private String createdBy; // 등록자

    @Column(name = "updated_at", columnDefinition = "TIMESTAMPTZ DEFAULT now()", nullable = false)
    private OffsetDateTime updatedAt; // 수정일시

    @Column(name = "updated_by", length = 50)
    private String updatedBy; // 수정자

    @Column(name = "recipe", length = 50)
    private String recipe; // 수정자
}
