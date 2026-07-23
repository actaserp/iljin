package mes.domain.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.sql.Timestamp;

/**
 * 도면 관리 (dwg_drawing)
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dwg_drawing")
public class DwgDrawing extends AbstractAuditModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "\"Spjangcd\"", length = 20)
    private String spjangcd;            // 사업장

    @Column(name = "\"Suju_id\"")
    private Integer sujuId;             // 수주(공정) 참조 - 도면 대상 식별자

    @Column(name = "\"ProjectNo\"", nullable = false, length = 50)
    private String projectNo;           // 프로젝트 번호

    @Column(name = "\"ProjectName\"", length = 200)
    private String projectName;         // 프로젝트명

    @Column(name = "\"LineName\"", length = 50)
    private String lineName;            // 라인

    @Column(name = "\"UnitCode\"", nullable = false, length = 50)
    private String unitCode;            // 유니트코드

    @Column(name = "\"ProcessName\"", length = 50)
    private String processName;         // 공정

    @Column(name = "\"Equipment_id\"")
    private Integer equipmentId;        // 설비 (equ.id 참조)

    @Column(name = "\"DrawingNo\"", length = 100)
    private String drawingNo;           // 도면번호

    @Column(name = "\"Version\"", nullable = false, length = 20)
    private String version;             // 버전

    @Column(name = "\"LatestYn\"", nullable = false, length = 1)
    private String latestYn = "Y";      // 최신도면여부

    @Column(name = "\"RegDate\"")
    private Timestamp regDate;          // 등록일

    @Column(name = "\"FileName\"", length = 200)
    private String fileName;            // 도면파일명

    @Column(name = "\"FilePath\"", length = 500)
    private String filePath;            // 도면파일경로

    @Column(name = "\"FileDataPk\"")
    private Integer fileDataPk;         // 첨부파일 참조

    @Column(name = "\"Remark\"", length = 500)
    private String remark;              // 비고
}
