package jo.edu.yu.yu_chatbot.recallToIndextheDataAgain;

import jo.edu.yu.yu_chatbot.crawler.WebsiteCrawlerRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionScheduler {

    private final WebsiteCrawlerRunner crawlerRunner;


    @Scheduled(fixedDelayString = "${ingestion.fixed-delay-ms:1209600000}")
    public void scheduledIngestion() {
        List<String> urls = List.of(
                "https://it.yu.edu.jo/images/CS/CS-study-plan_Arabic.pdf",

                "https://it.yu.edu.jo/",
                "https://science.yu.edu.jo/index.php/ar/",
                "https://cic.yu.edu.jo/",
                "https://nursing.yu.edu.jo/",
                "https://science.yu.edu.jo/",
                "https://engineering.yu.edu.jo/",
                "https://medicine.yu.edu.jo/",
                "https://pharmacy.yu.edu.jo/",
                "https://arts.yu.edu.jo/",
                "https://business.yu.edu.jo/",
                "https://sharia.yu.edu.jo/",
                "https://education.yu.edu.jo/",
                "https://lawfaculty.yu.edu.jo/",
                "https://mass.yu.edu.jo/",
                "https://sports.yu.edu.jo/",
                "https://archaeology.yu.edu.jo/",
                "https://tourism.yu.edu.jo/",
                "https://finearts.yu.edu.jo/",
                "https://dsa.yu.edu.jo/",
                "https://srgs.yu.edu.jo/",
                "https://langcenter.yu.edu.jo/",
                "https://yu.edu.jo/",

                "https://elc.yu.edu.jo/images/2025/04/27/Kafaa2025.pdf",

                "https://it.yu.edu.jo/index.php/home-ar/university-presidency-ar",
                "https://fmd.yu.edu.jo/ExternalAcademicsCard.aspx?Card=bHU8t9U5oX8=&Lang=Ar"
                ,"https://it.yu.edu.jo/index.php/en/faculty-members",
                "https://science.yu.edu.jo/index.php/ar/admissionar/stat-dept",
                "https://science.yu.edu.jo/index.php/ar/admissionar/apply-online",
                "https://science.yu.edu.jo/index.php/ar/admissionar/majors",
                "https://science.yu.edu.jo/index.php/ar/admissionar/physics-dept",
                "https://science.yu.edu.jo/index.php/ar/admissionar/undergraduate-admissions",
                "https://science.yu.edu.jo/index.php/ar/admissionar/earth-dept",
                "https://lawfaculty.yu.edu.jo/index.php/admissionar/2022-10-12-06-15-30",
                "https://lawfaculty.yu.edu.jo/index.php/admissionar/2022-10-12-06-17-12",
                "https://arts.yu.edu.jo/index.php/2022-10-12-18-50-17/arabic-dept-ar",
                "https://arts.yu.edu.jo/index.php/2022-10-12-18-50-17/english-dept-ar",
                "https://arts.yu.edu.jo/index.php/2022-10-12-18-50-17/history-dept-ar",
                "https://arts.yu.edu.jo/index.php/2022-10-12-18-50-17/mlanguage-dept-ar",
                "https://arts.yu.edu.jo/index.php/2022-10-12-18-50-17/political-dept-ar",
                "https://arts.yu.edu.jo/index.php/2022-10-12-18-50-17/sociology-dept-ar",
                "https://arts.yu.edu.jo/index.php/2022-10-12-18-50-17/simitic-dept-ar",
                "https://arts.yu.edu.jo/index.php/2022-10-12-18-50-17/geography-det-ar",
                "https://arts.yu.edu.jo/index.php/2022-10-12-18-50-17/translate-dept-ar",
                "https://arts.yu.edu.jo/index.php/2022-10-12-18-50-17/service-dept-ar",
                "https://archaeology.yu.edu.jo/index.php/ar/admissionar/undergraduate-admissions",
                "https://archaeology.yu.edu.jo/index.php/ar/admissionar/physics-dept",
                "https://archaeology.yu.edu.jo/index.php/ar/admissionar/apply-online",
                "https://sports.yu.edu.jo/index.php/depts-1ar/2022-09-30-22-02-57",
                "https://sports.yu.edu.jo/index.php/depts-1ar/2022-09-30-22-02-24",
                "https://sharia.yu.edu.jo/index.php/2022-10-10-07-22-35/fiqh-dept-ar",
                "https://sharia.yu.edu.jo/index.php/2022-10-10-07-22-35/usul-dept-ar",
                "https://sharia.yu.edu.jo/index.php/2022-10-10-07-22-35/islamiceco-dept-ar",
                "https://sharia.yu.edu.jo/index.php/2022-10-10-07-22-35/islamicstud-dept-ar",
                "https://finearts.yu.edu.jo/index.php/ar/admissionar/undergraduate-admissions",
                "https://finearts.yu.edu.jo/index.php/ar/admissionar/drama-dept",
                "https://finearts.yu.edu.jo/index.php/ar/admissionar/majors",
                "https://finearts.yu.edu.jo/index.php/ar/admissionar/apply-online",
                "https://finearts.yu.edu.jo/index.php/ar/admissionar/2023-10-15-07-34-41",
                "https://finearts.yu.edu.jo/index.php/ar/admissionar/2024-10-09-07-51-20",
                "https://pharmacy.yu.edu.jo/index.php/ar/2022-09-03-20-14-42/2022-09-02-20-42-27",
                "https://pharmacy.yu.edu.jo/index.php/ar/2022-09-03-20-14-42/2022-09-03-20-41-55",
                "https://pharmacy.yu.edu.jo/index.php/ar/2022-09-03-20-14-42/2022-09-03-20-45-40",
                "https://medicine.yu.edu.jo/index.php/admissionar/2022-10-12-06-15-30/bms-ar-menuar",
                "https://medicine.yu.edu.jo/index.php/admissionar/2022-10-12-06-15-30/patho-ar",
                "https://medicine.yu.edu.jo/index.php/admissionar/2022-10-12-06-17-12/medicine-ar-menu-2",
                "https://medicine.yu.edu.jo/index.php/admissionar/2022-10-12-06-17-12/surgery-ar-menu-2",
                "https://medicine.yu.edu.jo/index.php/admissionar/2022-10-12-06-17-12/pedia-ar-menu-3",
                "https://mass.yu.edu.jo/index.php/ar/admissionar/undergraduate-admissions",
                "https://mass.yu.edu.jo/index.php/ar/admissionar/tv-radio-dept",
                "https://mass.yu.edu.jo/index.php/ar/admissionar/majors",
                "https://elc.yu.edu.jo/images/kafaa_2025_1.PDF",
                "https://it.yu.edu.jo/index.php/2022-09-21-21-40-35/110-faculties/faculties-ar/255-2022-12-21-07-16-53",
                "https://it.yu.edu.jo/index.php/2022-09-21-21-05-49",
                ""


                ,"https://medicine.yu.edu.jo/index.php/en/bms-department-members-en"
                ,"https://fmd.yu.edu.jo/"
                ,"https://it.yu.edu.jo/index.php/en/home-en/university-presidency",
                "https://it.yu.edu.jo/index.php/home-ar/2025-04-07-06-42-05",
                "https://it.yu.edu.jo/index.php/newsar/386-huawei-ambassador-university-program-jordan"
        );

        log.info("Running scheduled ingestion for {} urls", urls.size());
        crawlerRunner.ingestUrls(urls);
    }
}