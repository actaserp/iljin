package mes.domain.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 수입검사기준서 - 헤더 (기준서 1장 = 품목 + 협력사)
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tb_incoming_insp01")
public class Tb_incoming_insp01 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "item_code", nullable = false, length = 50)
    private String itemCode; // 품목코드

    @Column(name = "item_name", length = 200)
    private String itemName; // 품목명

    @Column(name = "supplier_code", length = 50)
    private String supplierCode; // 협력사코드 (NULL = 공통기준)

    @Column(name = "supplier_name", length = 200)
    private String supplierName; // 협력사명

    @Column(name = "drawing_no", length = 100)
    private String drawingNo; // 도면번호

    @Column(name = "rev_no", length = 50)
    private String revNo; // 개정번호(Rev)

    @Column(name = "inspect_type", nullable = false, length = 20)
    private String inspectType = "SAMPLING"; // 검사구분 SAMPLING/FULL

    @Column(name = "aql", length = 20)
    private String aql; // AQL

    @Column(name = "issue_date")
    private LocalDate issueDate; // 제정일자

    @Column(name = "rev_date")
    private LocalDate revDate; // 개정일자

    @Column(name = "use_yn", nullable = false, length = 1)
    private String useYn = "Y"; // 사용여부

    @Column(name = "remark")
    private String remark; // 비고

    @Column(name = "spjangcd", length = 20)
    private String spjangcd; // 사업장코드

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ DEFAULT now()", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMPTZ DEFAULT now()", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;
}
