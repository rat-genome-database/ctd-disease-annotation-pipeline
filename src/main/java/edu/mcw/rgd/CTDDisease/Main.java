package edu.mcw.rgd.CTDDisease;

import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.Utils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

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
    private String staleAnnotDeleteThreshold;

    Dao dao;
    FileParser fileParser;
    QC qc;

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
            // print stack trace to error stream
            ByteArrayOutputStream bs = new ByteArrayOutputStream();
            e.printStackTrace(new PrintStream(bs));
            manager.log.error(bs.toString());
            throw e;
        }
    }

    void init(DefaultListableBeanFactory bf) {

        log.info(getVersion());

        fileParser = (FileParser) bf.getBean("fileParser");
        qc = (QC) bf.getBean("qc");
        qc.setDao(dao);
    }

    /**
     * print connection information, download the genes-diseases file from CTD, parse it, QC it and load the annotations into RGD
     * @throws Exception
     */
    public void run() throws Exception {

        long time1 = System.currentTimeMillis();

        // display connection info
        log.info("   "+dao.getConnectionInfo());

        SimpleDateFormat sdt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        log.info("   started at "+sdt.format(new Date(time1)));

        CounterPool counters = new CounterPool();
        Date time0 = new Date();
        int originalAnnotCount = dao.getAnnotationsModifiedBeforeTimestamp(time0).size();
        counters.add("ANNOT COUNT ORIGINAL", originalAnnotCount);


        qc.setCounters(counters);

        List<Record> incomingRecords = fileParser.process(counters);

        log.info("INCOMING RECORDS: "+incomingRecords.size());

        // randomize incoming records to limit the chance of conflicts when processing in highly parallel environment
        Collections.shuffle(incomingRecords);

        incomingRecords.parallelStream().forEach( rec-> {
            try {
                qc.process(rec);
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        });

        dao.deleteObsoleteAnnotations(counters, time0, originalAnnotCount, getStaleAnnotDeleteThreshold());

        // dump counter statistics
        log.info(counters.dumpAlphabetically());

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

    public void setStaleAnnotDeleteThreshold(String staleAnnotDeleteThreshold) {
        this.staleAnnotDeleteThreshold = staleAnnotDeleteThreshold;
    }

    public String getStaleAnnotDeleteThreshold() {
        return staleAnnotDeleteThreshold;
    }
}
