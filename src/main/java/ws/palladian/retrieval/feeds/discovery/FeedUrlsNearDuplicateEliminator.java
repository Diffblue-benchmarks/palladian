package ws.palladian.retrieval.feeds.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections15.MultiMap;
import org.apache.commons.collections15.multimap.MultiHashMap;
import org.apache.commons.lang.ArrayUtils;
import org.apache.log4j.Logger;

import ws.palladian.helper.FileHelper;
import ws.palladian.helper.LineAction;
import ws.palladian.helper.UrlHelper;

/**
 * Quickndirty implementation for Sandro's highly sophisticated
 * Feed-URLs-Near-Duplicate-Detection-Algorithm(tm)(c)(reg).
 * 
 * @author Philipp Katz
 * @author Sandro Reichert
 */
public class FeedUrlsNearDuplicateEliminator {

    /** The logger for this class. */
    private static final Logger LOGGER = Logger.getLogger(FeedUrlsNearDuplicateEliminator.class);

    // be sure, to sort the Strings in a way, so that no String in the Array is contained in its successor
    private static final String[] ATOM = new String[] { "atom10", "atom" };
    private static final String[] RSS = new String[] { "rss_2.0", "rss2.0", "rss200", "rss20", "rss2", "rss" };

    /** A format string must not be preceded by a word character [a-zA-Z0-9] */
    private static final String START_PATTERN = "(?<!\\w)";

    /** A format string must not be followed by a word character [a-zA-Z0-9] */
    private static final String STOP_PATTERN = "(?!\\w)";

    /** The compiled pattern for all formats. */
    private static Pattern formatPattern;

    /** All available formats; Atom first, as it is the preferred format. */
    private static final String[] FORMATS = (String[]) ArrayUtils.addAll(ATOM, RSS);

    /** Place holder for temporary replacements. */
    private static final String FORMAT_PLACEHOLDER = "###FORMAT###";

    /** Ignore all feed URLs containing this pattern. */
    private static final Pattern IGNORE_PATTERN = Pattern.compile("sessionid|PHPSESSID", Pattern.CASE_INSENSITIVE);

    /**
     * Initiate compiling of the pattern without the need for an constructor.
     */
    static {
        compilePattern();
    }

    public static void main(String[] args) {

        // final String inputFile = "/home/pk/Desktop/FeedDiscovery/foundFeedsDeduplicatedSorted.txt";
        // final String outputFile = "/home/pk/Desktop/FeedDiscovery/foundFeedsRemovedNearDuplicates.txt";
        final String inputFile = "Z:/in_out/feedDatasetPaper/foundFeedsDeduplicatedSorted.txt";
        final String outputFile = "Z:/in_out/feedDatasetPaper/foundFeedsDeduplicatedSortedRemovedNearDuplicates.txt";

        /** Collect links for each domain. */
        final Queue<String> linkQueue = new LinkedList<String>();

        LineAction lineAction = new LineAction() {

            String domain = null;

            @Override
            public void performAction(String line, int lineNumber) {
                if (lineNumber % 10000 == 0) {
                    LOGGER.info(lineNumber + " lines processed.");
                }
                String currentDomain = UrlHelper.getDomain(line);
                // the current Domain differs from the previous iteration;
                // do the de-duplication on the URLs in the queue and write them out
                boolean nextDomain = !currentDomain.equalsIgnoreCase(domain) && domain != null;
                if (nextDomain) {
                    List<String> deDuplicated = deDuplicate(linkQueue);
                    appendFile(outputFile, deDuplicated);
                    linkQueue.clear();
                }
                linkQueue.add(line);
                domain = currentDomain;
            }
        };
        FileHelper.delete(outputFile);
        FileHelper.performActionOnEveryLine(inputFile, lineAction);

        // write the rest of the URLs in the queue
        List<String> deDuplicated = deDuplicate(linkQueue);
        appendFile(outputFile, deDuplicated);

    }

    /**
     * Compiles the pattern. Pattern should look like (?<!\w)(atom10|atom|rss_2.0|rss2.0|rss200|rss20|rss2|rss)(?!\w)
     */
    private static void compilePattern() {
        StringBuilder formatPatternBuilder = new StringBuilder(); 
        formatPatternBuilder.append(START_PATTERN).append("(");
        for (String format : FORMATS) {
            formatPatternBuilder.append(format).append("|");
        }
        formatPatternBuilder.deleteCharAt(formatPatternBuilder.length() - 1);
        formatPatternBuilder.append(")").append(STOP_PATTERN);
        LOGGER.debug(formatPatternBuilder.toString());
        formatPattern = Pattern.compile(formatPatternBuilder.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * For a given {@link Collection} of strings (feed URLs), all near duplicates are eliminated. If more than one feed
     * format is found and if we ha a Atom feed, this one is chosen, otherwise take the first from what we have.
     * 
     * @param linkQueue the urls to check for near duplicates
     * @return One format per feed. If more than one feed format is found and if we ha a Atom feed, this one is chosen,
     *         otherwise take the first from what we have.
     */
    public static List<String> deDuplicate(Collection<String> linkQueue) {

        // list with result, candidates without explicitly given format are added directly
        List<String> result = new ArrayList<String>();

        // map contains [ url-with-placeholder ; [ format1; format2; format3; ...] ]
        MultiMap<String, String> temp = new MultiHashMap<String, String>();

        for (String link : linkQueue) {
            if (IGNORE_PATTERN.matcher(link).find()) {
                continue;
            }
            String format = null;
            link = link.trim();

            LOGGER.debug("link : " + link);

            Matcher matcher = formatPattern.matcher(link);
            while (matcher.find()) {
                if (matcher.groupCount() == 1) {
                    format = matcher.group(1);
                    LOGGER.debug("   format : " + format);
                    link = link.replaceAll(formatPattern.toString(), FORMAT_PLACEHOLDER);
                    break;
                } else if (matcher.groupCount() > 1) {
                    LOGGER.fatal("found too many feed formats in : " + link);
                }
            }

            if (format != null) {
                temp.put(link, format);
            } else {
                result.add(link);
            }
        }

        // find out the "best" alternative; if we have an Atom feed, take this,
        // elsewise take the first from what we have
        Set<Entry<String, Collection<String>>> entrySet = temp.entrySet();
        for (Entry<String, Collection<String>> entry : entrySet) {
            String link = entry.getKey();
            Collection<String> candidates = entry.getValue();
            for (String format : FORMATS) {
                if (candidates.contains(format)) {
                    link = link.replace(FORMAT_PLACEHOLDER, format);
                    result.add(link);
                    break;
                }
            }
        }

        // remove "overlap", e.g. [ "http://peterdowdall.com/feed/", "http://peterdowdall.com/feed/atom/" ]
        // is reduced to ""http://peterdowdall.com/feed/atom/", because the whole string is contained in the other
        ListIterator<String> listIterator = result.listIterator();
        while (listIterator.hasNext()) {
            String current = listIterator.next();
            ListIterator<String> listIteratorInner = result.listIterator();
            while (listIteratorInner.hasNext()) {
                String key = listIteratorInner.next();
                if (!key.equals(current) && key.indexOf(current) != -1) {
                    listIterator.remove();
                    break;
                }
            }
        }

        // case deduplication; remove duplicates with different cases
        Set<String> caseDeDup = new HashSet<String>();
        listIterator = result.listIterator();
        while (listIterator.hasNext()) {
            String current = listIterator.next();
            boolean isCaseDuplicate = !caseDeDup.add(current.toLowerCase());
            if (isCaseDuplicate) {
                listIterator.remove();
            }
        }

        // LOGGER.info(linkQueue + " -> " + result);
        return result;
    }

    /**
     * Append some lines to a file.
     * 
     * @param filePath Path to file to append to.
     * @param lines The lines to append.
     */
    private static void appendFile(String filePath, Collection<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        FileHelper.appendFile(filePath, sb.toString());
    }

}