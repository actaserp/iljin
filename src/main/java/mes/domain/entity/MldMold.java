package mes.domain.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDate;

/**
 * 금형 관리 (mld_mold)
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "mld_mold")
public class MldMold extends AbstractAuditModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "\"MoldCode\"", nullable = false, length = 50)
    private String moldCode;        // 금형번호

    @Column(name = "\"MoldName\"", length = 200)
    private String moldName;        // 금형명

    @Column(name = "\"Standard\"", length = 200)
    private String standard;        // 규격

    @Column(name = "\"Material\"", length = 100)
    private String material;        // 재질

    @Column(name = "\"Company_id\"")
    private Integer companyId;      // 제작업체

    @Column(name = "\"CompanyName\"", length = 200)
    private String companyName;     // 제작업체명

    @Column(name = "\"MakeDate\"")
    private LocalDate makeDate;     // 제작일

    @Column(name = "\"Location\"", length = 200)
    private String location;        // 보관위치

    @Column(name = "\"Status\"", nullable = false, length = 20)
    private String status = "USING"; // USING / REPAIR / SCRAP

    @Column(name = "\"FileDataPk\"")
    private Integer fileDataPk;     // 첨부파일

    @Column(name = "\"FileName\"", length = 200)
    private String fileName;        // 첨부 파일명

    @Column(name = "\"Remark\"")
    private String remark;          // 비고

    @Column(name = "\"Spjangcd\"", length = 20)
    private String spjangcd;        // 사업장
}
