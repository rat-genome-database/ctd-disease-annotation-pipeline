package edu.mcw.rgd.CTDDisease;

import edu.mcw.rgd.pipelines.PipelineManager;
import edu.mcw.rgd.process.Utils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Date;

/**
 * @author mtutaj
 * GOAL: download file with disease annotations from CTD and import them into RGD
 * <ul>
 * <li>stale annotations module should abort deletion if there is more than 10% of annotations to be deleted
 * <li>when deleting stale annotations, do not delete annotations created by DoAnnotator pipeline
 * <li>insert only those annotations that are unique from OMIM disease annotations (per RGDD-1386)
 * </ul>
 */
public class Main {

    private String version;
    private int qcThreadCount;
    private String staleAnnotDeleteThreshold;

    Dao dao;
    FileParser fileParser;
    QC qc;
    QCAnnot qcAnnot;
    Loader loader;

    Logger log = LogManager.getLogger("status");

    /**
     * load spring configuration from properties/AppConfigure.xml file
     * and run the pipeline
     * @param args cmdline args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {

        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
        Main manager = (Main) bf.getBean("main");

        manager.init(bf);

        try {
            manager.run();
        } catch(Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    void init(DefaultListableBeanFactory bf) {

        log.info(getVersion());
        fileParser = (FileParser) bf.getBean("fileParser");

        qc = (QC) bf.getBean("qc");
        qc.setDao(dao);

        qcAnnot = new QCAnnot();
        qcAnnot.setDao(dao);

        loader = (Loader) bf.getBean("loader");
        loader.setDao(dao);
    }

    /**
     * print connection information, download the genes-diseases file from CTD, parse it, QC it and load the annotations into RGD
     * @throws Exception
     */
    public void run() throws Exception {

        long time1 = System.currentTimeMillis();

        // display connection info
        log.info(dao.getConnectionInfo());

        PipelineManager manager = new PipelineManager();

        Date time0 = new Date();
        int originalAnnotCount = dao.getAnnotationsModifiedBeforeTimestamp(time0).size();
        manager.getSession().incrementCounter("ANNOT COUNT ORIGINAL", originalAnnotCount);

        manager.addPipelineWorkgroup(fileParser, "FP", 1, 0);
        manager.addPipelineWorkgroup(qc, "QC", getQcThreadCount(), 0);
        manager.addPipelineWorkgroup(qcAnnot, "QA", 1, 0);
        manager.addPipelineWorkgroup(loader, "LD", 1, 0);

        // because we are doing annotation QC and loading in parallel thread, conflicts could happen
        // resulting in an attempt to insert duplicate annotations;
        // we do allow for up-to 100 duplicate annotations to be resolved later
        manager.getSession().setAllowedExceptions(100);

        // violations of unique key during inserts of annotations will be handled silently,
        // without writing anything to the logs
        manager.getSession().registerUserException(new String[]{
                "FULL_ANNOT_MULT_UC", "DataIntegrityViolationException", "SQLIntegrityConstraintViolationException"});

        manager.run();

        dao.deleteObsoleteAnnotations(manager.getSession(), time0, originalAnnotCount, getStaleAnnotDeleteThreshold());

        // dump counter statistics
        manager.getSession().dumpCounters(log);

        log.info("=== OK === elapsed time "+Utils.formatElapsedTime(time1, System.currentTimeMillis()));
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public Dao getDao() {
        return dao;
    }

    public void setDao(Dao dao) {
        this.dao = dao;
    }

    public void setQcThreadCount(int qcThreadCount) {
        this.qcThreadCount = qcThreadCount;
    }

    public int getQcThreadCount() {
        return qcThreadCount;
    }

    public void setStaleAnnotDeleteThreshold(String staleAnnotDeleteThreshold) {
        this.staleAnnotDeleteThreshold = staleAnnotDeleteThreshold;
    }

    public String getStaleAnnotDeleteThreshold() {
        return staleAnnotDeleteThreshold;
    }
}
