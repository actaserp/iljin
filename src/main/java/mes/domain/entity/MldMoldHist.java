package mes.domain.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * 금형 버전 이력 (mld_mold_hist)
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "mld_mold_hist")
public class MldMoldHist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "\"Mold_id\"", nullable = false)
    private Integer moldId;

    @Column(name = "\"Version\"", nullable = false)
    private Integer version;

    @Column(name = "\"RegDate\"")
    private LocalDate regDate;

    @Column(name = "\"FileDataPk\"")
    private Integer fileDataPk;

    @Column(name = "\"FileName\"", length = 200)
    private String fileName;

    @Column(name = "\"Remark\"", length = 500)
    private String remark;

    @Column(name = "_created")
    private Timestamp _created;

    @Column(name = "_creater_id")
    private Integer _creater_id;
}
