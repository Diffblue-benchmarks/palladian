package ws.palladian.preprocessing.scraping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import ws.palladian.extraction.PageAnalyzer;
import ws.palladian.extraction.XPathSet;
import ws.palladian.helper.UrlHelper;
import ws.palladian.helper.html.HTMLHelper;
import ws.palladian.helper.html.XPathHelper;
import ws.palladian.helper.nlp.StringHelper;
import ws.palladian.helper.nlp.Tokenizer;
import ws.palladian.retrieval.DocumentRetriever;
import ws.palladian.retrieval.resources.WebImage;

/**
 * <p>
 * The PageSentenceExtractor extracts clean sentences from (English) texts. That is, short phrases are not included in
 * the output. Consider the {@link ReadabilityContentExtractor} for general content. The main difference is that this class
 * also finds sentences in comment sections of web pages.
 * </p>
 * 
 * <p>
 * Score on boilerplate dataset: 0.76088387 (r1505);
 * </p>
 * 
 * @author David Urbansky
 * 
 */
public class PalladianContentExtractor extends WebPageContentExtractor {

    /** The logger for this class. */
    private static final Logger LOGGER = Logger.getLogger(ReadabilityContentExtractor.class);

    private Document document;
    private Node resultNode;

    private List<String> sentences = new ArrayList<String>();
    private String mainContentHTML = "";
    private String mainContentText = "";

    private List<WebImage> imageURLs;

    public PalladianContentExtractor() {
        crawler = new DocumentRetriever();
    }

    @Override
    public PalladianContentExtractor setDocument(String url) {
        crawler.setFeedAutodiscovery(false);
        return setDocument(crawler.getWebDocument(url));
    }

    @Override
    public PalladianContentExtractor setDocument(Document document) {
        this.document = document;
        imageURLs = null;
        parseDocument();
        return this;
    }

    public DocumentRetriever getCrawler() {
        return crawler;
    }

    public void setCrawler(DocumentRetriever crawler) {
        this.crawler = crawler;
    }


    public Document getDocument() {
        return document;
    }

    public List<String> getSentences() {
        return sentences;
    }

    private void parseDocument() {

        String content = HTMLHelper.documentToText(document);
        sentences = Tokenizer.getSentences(content, true);

        PageAnalyzer pa = new PageAnalyzer();
        pa.setDocument(getDocument());
        XPathSet xpathset = new XPathSet();

        Set<String> uniqueSentences = new HashSet<String>(sentences);
        for (String sentence : uniqueSentences) {
            Set<String> xPaths = pa.constructAllXPaths(sentence);
            for (String xPath : xPaths) {
                xPath = PageAnalyzer.removeXPathIndicesFromLastCountNode(xPath);
                xpathset.add(xPath);
            }
        }

        // take the shortest xPath that has a count of at least 50% of the xPath with the highest count
        LinkedHashMap<String, Integer> xpmap = xpathset.getXPathMap();
        String highestCountXPath = xpathset.getHighestCountXPath();
        int highestCount = xpathset.getCountOfXPath(highestCountXPath);

        String shortestMatchingXPath = highestCountXPath;
        for (Entry<String, Integer> mapEntry : xpmap.entrySet()) {
            if (mapEntry.getKey().length() < shortestMatchingXPath.length()
                    && mapEntry.getValue() / (double) highestCount >= 0.5) {
                shortestMatchingXPath = mapEntry.getKey();
            }
        }

        // String highestCountXPath = xpathset.getHighestCountXPath();
        shortestMatchingXPath = XPathHelper.getParentXPath(shortestMatchingXPath);
        shortestMatchingXPath = shortestMatchingXPath.replace("HTML/BODY", "");
        shortestMatchingXPath = shortestMatchingXPath.replace("xhtml:HTML/xhtml:BODY", "");
        // /xhtml:HTML/xhtml:BODY/xhtml:DIV[3]/xhtml:TABLE[1]/xhtml:TR[1]/xhtml:TD[1]/xhtml:TABLE[1]/xhtml:TR[2]/xhtml:TD[1]/xhtml:P/xhtml:FONT
        // shortestMatchingXPath =
        // "//xhtml:DIV[3]/xhtml:TABLE[1]/xhtml:TR[1]/xhtml:TD[1]/xhtml:TABLE[1]/xhtml:TR[2]/xhtml:TD[1]/xhtml:P/xhtml:FONT";
        resultNode = XPathHelper.getNode(getDocument(), shortestMatchingXPath);
        if (resultNode == null) {
            LOGGER.warn("could not get main content node for URL: " + getDocument().getDocumentURI());
            return;
        }

        mainContentHTML = HTMLHelper.documentToHTMLString(resultNode);

        // mainContentHTML = mainContentHTML.replaceAll("\n{2,}","");
        mainContentText = HTMLHelper.documentToReadableText(resultNode);

        // System.out.println(mainContentHTML);
        // System.out.println(mainContentText);
    }


    public List<WebImage> getImages(String fileType) {

        List<WebImage> filteredImages = new ArrayList<WebImage>();
        String ftSmall = fileType.toLowerCase();
        for (WebImage webImage : getImages()) {
            if (webImage.getType().toLowerCase().equalsIgnoreCase(ftSmall)) {
                filteredImages.add(webImage);
            }
        }

        return filteredImages;
    }


    public List<WebImage> getImages() {

        if (imageURLs != null) {
            return imageURLs;
        }

        imageURLs = new ArrayList<WebImage>();

        // we need to query the result document with an xpath but the name space check has to be done on the original
        // document
        String imgXPath = "//IMG";
        // if (XPathHelper.hasXhtmlNs(document)) {
        // imgXPath = XPathHelper.addXhtmlNsToXPath(imgXPath);
        // }

        List<Node> imageNodes = XPathHelper.getXhtmlChildNodes(resultNode, imgXPath);
        for (Node node : imageNodes) {
            try {

                WebImage webImage = new WebImage();

                NamedNodeMap nnm = node.getAttributes();
                String imageURL = nnm.getNamedItem("src").getTextContent();

                if (!imageURL.startsWith("http")) {
                    if (imageURL.startsWith("/")) {
                        imageURL = UrlHelper.getDomain(getDocument().getDocumentURI()) + imageURL;
                    } else {
                        imageURL = getDocument().getDocumentURI() + imageURL;
                    }
                }

                webImage.setUrl(imageURL);

                if (nnm.getNamedItem("alt") != null) {
                    webImage.setAlt(nnm.getNamedItem("alt").getTextContent());
                }
                if (nnm.getNamedItem("title") != null) {
                    webImage.setTitle(nnm.getNamedItem("title").getTextContent());
                }
                if (nnm.getNamedItem("width") != null) {
                    String w = nnm.getNamedItem("width").getTextContent();
                    w.replace("px", "");
                    webImage.setWidth(Integer.parseInt(w));
                }
                if (nnm.getNamedItem("height") != null) {
                    String h = nnm.getNamedItem("height").getTextContent();
                    h.replace("px", "");
                    webImage.setHeight(Integer.parseInt(h));
                }

                imageURLs.add(webImage);

            } catch (NullPointerException e) {
                LOGGER.warn("an image has not all necessary attributes");
            }
        }

        return imageURLs;
    }

    @Override
    public Node getResultNode() {
        return resultNode;
    }

    public String getMainContentHTML() {
        return mainContentHTML;
    }


    @Override
    public String getResultText(){
        return mainContentText;
    }

    @Deprecated
    public static String getText(String url) {

        PalladianContentExtractor pse = new PalladianContentExtractor();
        pse.setDocument(url);

        StringBuilder text = new StringBuilder();
        List<String> sentences = pse.getSentences();

        for (String string : sentences) {
            text.append(string).append(" ");
        }

        return text.toString();
    }

    public String getSentencesString() {
        StringBuilder text = new StringBuilder();
        List<String> sentences = getSentences();

        for (String string : sentences) {
            text.append(string).append(" ");
        }

        return text.toString();
    }



    @Override
    public String getResultTitle() {
        // TODO Needs better implementation.
    	String resultTitle = StringHelper.getFirstWords(mainContentText, 20);
        return resultTitle;
    }

    /**
     * @param args
     * @throws PageContentExtractorException
     */
    public static void main(String[] args) throws PageContentExtractorException {

        // String url = "http://lifehacker.com/5690722/why-you-shouldnt-switch-your-email-to-facebook";
        // String url = "http://stackoverflow.com/questions/2670082/web-crawler-that-can-interpret-javascript";
        // System.out.println("SentenceExtractor: " + PageSentenceExtractor.getText(url));
        // System.out.println("ContentExtractor:  " + new PageContentExtractor().getResultText(url));

        // PageContentExtractor pe = new PageContentExtractor();
        // pe.setDocument("http://jezebel.com/5733078/can-you-wear-six-items-or-less");
        // System.out.println(pe.getResultText());
        // CollectionHelper.print(pe.getImages());

        PalladianContentExtractor pe = new PalladianContentExtractor();
        WebPageContentExtractor pe2 = new ReadabilityContentExtractor();
        // pe.setDocument("http://www.allaboutbirds.org/guide/Peregrine_Falcon/lifehistory");
        pe.setDocument("http://www.hollyscoop.com/cameron-diaz/52.aspx");

        // CollectionHelper.print(pe.setDocument("http://www.bbc.co.uk/news/science-environment-12209801").getImages());
        System.out.println("Result Text: "+pe2.getResultText());
        System.out.println(pe.getResultText());
        System.out.println("Title:"+pe.getResultTitle());
        // CollectionHelper.print(pe.getSentences());

        // CollectionHelper.print(pe.setDocument(
        // "data/datasets/L3S-GN1-20100130203947-00001/original/2281f3c1-7a86-4c4c-874c-b19964e588f1.html")
        // .getImages());
        // System.out.println(pe.getMainContentText());
        //
        // CollectionHelper.print(pe.setDocument("http://jezebel.com/5733078/can-you-wear-six-items-or-less").getImages());
        // System.out.println(pe.getMainContentText());
        //
        // CollectionHelper.print(pe.setDocument("http://www.bbc.co.uk/news/science-environment-12190895")
        // .getImages("jpg"));
        // System.out.println(pe.getMainContentText());
        //
        // CollectionHelper.print(pe.setDocument("http://lifehacker.com/5715912/how-to-plant-ideas-in-someones-mind")
        // .getImages("jpg"));
        // System.out.println(pe.getMainContentText());
        //
        // CollectionHelper.print(pe.setDocument(
        // "http://edition.cnn.com/2011/WORLD/europe/01/14/italy.berlusconi/index.html").getImages("jpg"));
        // System.out.println(pe.getMainContentText());

    }

}