package com.github.eclipsecolortheme;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.Bundle;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.github.eclipsecolortheme.mapper.GenericMapper;

/** Loads and applies color themes. */
public final class ColorThemeManager implements IPropertyChangeListener {

	private Map<String, ColorTheme> themes;
	//Note: the editors field was moved to ColorThemeApplier.editors
	private IPreferenceStore preferenceStore;

	/**
	 * Can be null if we have no current theme!
	 */
	private String currentThemeName;

	/**
	 * Cache for stock themes.
	 */
	private static Map<String, ColorTheme> stockThemes;

	private static ColorThemeManager singleton;

	public static ColorThemeManager getSingleton() {
		if (singleton == null) {
			singleton = new ColorThemeManager();
		}
		return singleton;
	}

	public void propertyChange(PropertyChangeEvent event) {
		if (Activator.CURRENT_COLOR_THEME.equals(event.getProperty())) {
			Object newValue = event.getNewValue();
			if (newValue != null) {
				currentThemeName = newValue.toString();
			} else {
				currentThemeName = null;
			}
			foregroundCache = null;
			backgroundCache = null;
		}
	}

	/** Creates a new color theme manager. */
	private ColorThemeManager() {
		themes = new HashMap<String, ColorTheme>();
		Activator plugin = Activator.getDefault();
		if(plugin != null){
			preferenceStore = plugin.getPreferenceStore();
			currentThemeName = preferenceStore
					.getString(Activator.CURRENT_COLOR_THEME);
			preferenceStore.addPropertyChangeListener(this);
			
			if (stockThemes == null) {
				stockThemes = new HashMap<String, ColorTheme>();
				readStockThemes(stockThemes);
				
			}
			themes.putAll(stockThemes);
			readImportedThemes(themes);
		}
	}

	private void readStockThemes(Map<String, ColorTheme> themes) {
		IConfigurationElement[] config = Platform
				.getExtensionRegistry()
				.getConfigurationElementsFor(Activator.EXTENSION_POINT_ID_THEME);
		try {
			for (IConfigurationElement e : config) {
				String xml = e.getAttribute("file");
				String contributorPluginId = e.getContributor().getName();
				Bundle bundle = Platform.getBundle(contributorPluginId);
				try(InputStream input = (InputStream) bundle.getResource(xml)
						.getContent()){
					ParsedTheme theme = parseTheme(input, false);
					amendThemeEntries(theme.getTheme().getEntries());
					themes.put(theme.getTheme().getName(), theme.getTheme());
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void readImportedThemes(Map<String, ColorTheme> themes) {
		IPreferenceStore store = getPreferenceStore();

		// try to read 100 themes always (ok, we may fail if he has more than
		// 100 themes,
		// but that seems a reasonable tradeoff now that we can remove themes,
		// so, we can have 'holes' in the numbering).
		for (int i = 1; i < 100; i++) {
			String importedThemeId = "importedColorTheme" + i;
			String xml = store.getString(importedThemeId);
			if (xml == null || xml.length() == 0)
				continue;
			try {
				ParsedTheme theme = parseTheme(
						new ByteArrayInputStream(xml.getBytes("UTF-8")), false);
				theme.getTheme().setImportedThemeId(importedThemeId);
				amendThemeEntries(theme.getTheme().getEntries());
				themes.put(theme.getTheme().getName(), theme.getTheme());
			} catch (Exception e) {
				System.err.println("Error while parsing imported theme");
				e.printStackTrace();
			}
		}
	}

	public void clearImportedThemes() {
		IPreferenceStore store = getPreferenceStore();
		for (int i = 1; i < 100; i++) {
			store.setToDefault("importedColorTheme" + i);
		}
		themes.clear();
		readStockThemes(themes);
	}

	private IPreferenceStore getPreferenceStore() {
		return preferenceStore;
	}

	/**
	 * Parses theme file.
	 *
	 * @param input
	 *            The input for theme file.
	 * @param loadSource
	 *            Specify if should load original XML source.
	 * @return Parsed theme
	 * @throws Exception 
	 */
	public static ParsedTheme parseTheme(InputStream input, boolean loadSource)
			throws Exception {
		ColorTheme theme = new ColorTheme();
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setFeature("http://xml.org/sax/features/namespaces", false);
        factory.setFeature("http://xml.org/sax/features/validation", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		DocumentBuilder builder = factory.newDocumentBuilder();
		
		builder.setEntityResolver(new EntityResolver() {
            public InputSource resolveEntity(String pid, String sid) throws SAXException {
                //Disabling things above should work, but just in case, let's also set it not to
                //resolve any entities.
                return new InputSource(new StringReader(""));
            }
        });

		
		Document document = builder.parse(input);
		Element root = document.getDocumentElement();
		String nodeName = root.getNodeName();
		Map<String, ColorThemeSetting> entries;
		if(nodeName.equals("colorTheme")){
			entries = loadEclipseColorTheme(root, theme);
		}else{
			entries = TmThemeLoader.loadTmTheme(document, theme);
		}
		
		// Fabioz Change: amend entries before setting them
		amendThemeEntries(entries);
		theme.setEntries(entries);

		// Set the mappingOverrides
        NodeList nodeListMappingOverrides = root.getElementsByTagName("mappingOverrides");
        if (nodeListMappingOverrides.getLength() > 0) {
            Element mappingOverridesRoot = (Element) nodeListMappingOverrides.item(0);

            Map<String, Map<String, ColorThemeMapping>> mappings = new HashMap<String, Map<String, ColorThemeMapping>>();
            NodeList nodeListEclipseColorThemeMapping = mappingOverridesRoot.getChildNodes();
            for (int i = 0; i < nodeListEclipseColorThemeMapping.getLength(); ++i) {
                Node eclipseColorThemeMapping = nodeListEclipseColorThemeMapping.item(i);
                if (eclipseColorThemeMapping.hasAttributes()) {
                    String pluginId = eclipseColorThemeMapping.getAttributes().getNamedItem("plugin").getNodeValue();
                    Map<String, ColorThemeMapping> mapMappings = new HashMap<String, ColorThemeMapping>();

                    new GenericMapper().parseMappings((Element) eclipseColorThemeMapping, mapMappings);
                    mappings.put(pluginId, mapMappings);
                }
            }
            theme.setMappings(mappings);
        }

		ParsedTheme parsedTheme = new ParsedTheme(theme);
		if (loadSource)
			parsedTheme.setSource(documentToString(document));
		return parsedTheme;
	}

	private static Map<String, ColorThemeSetting> loadEclipseColorTheme(Element root, ColorTheme theme) {
		theme.setId(root.getAttribute("id"));
		theme.setName(root.getAttribute("name"));
		theme.setAuthor(root.getAttribute("author"));
		theme.setWebsite(root.getAttribute("website"));

		Map<String, ColorThemeSetting> entries = new HashMap<String, ColorThemeSetting>();
		NodeList entryNodes = root.getChildNodes();
		for (int i = 0; i < entryNodes.getLength(); i++) {
			Node entryNode = entryNodes.item(i);
			if (entryNode.hasAttributes()) {
				NamedNodeMap attributes = entryNode.getAttributes();
				String color = attributes.getNamedItem("color")
						.getNodeValue();
				Node nodeBackgroundColor = attributes.getNamedItem("backgroundColor");
				Node nodeBold = attributes.getNamedItem("bold");
				Node nodeItalic = attributes.getNamedItem(
						"italic");
				Node nodeUnderline = attributes.getNamedItem(
						"underline");
				Node nodeUseCustomFont = attributes.getNamedItem(
						"useCustomFont");
				Node nodeStrikethrough = attributes
						.getNamedItem("strikethrough");
				
				Node nodeBackgroundEnabled = attributes
						.getNamedItem("useCustomBackground");
				
				Node nodeFont = attributes
						.getNamedItem("font");
				
				ColorThemeSetting setting = new ColorThemeSetting(color);
				if(nodeBackgroundColor != null){
					setting.setBackgroundColor(nodeBackgroundColor.getNodeValue());
				}
				if(nodeFont != null){
					setting.setFont(nodeFont.getNodeValue());
				}
				if(nodeUseCustomFont != null){
					setting.setUseCustomFont(Boolean.parseBoolean(nodeUseCustomFont
							.getNodeValue()));
				}
				if (nodeBold != null)
					setting.setBoldEnabled(Boolean.parseBoolean(nodeBold
							.getNodeValue()));
				if (nodeItalic != null)
					setting.setItalicEnabled(Boolean.parseBoolean(nodeItalic
							.getNodeValue()));
				if (nodeStrikethrough != null)
					setting.setStrikethroughEnabled(Boolean
							.parseBoolean(nodeStrikethrough.getNodeValue()));
				if (nodeUnderline != null)
					setting.setUnderlineEnabled(Boolean
							.parseBoolean(nodeUnderline.getNodeValue()));
				if (nodeBackgroundEnabled != null)
					setting.setUseCustomBackground(Boolean
							.parseBoolean(nodeBackgroundEnabled.getNodeValue()));
				entries.put(entryNode.getNodeName(), setting);
				TmThemeLoader.onFoundElement(entryNode.getNodeName(), setting, entries, false);
			}
		}
		return entries;
	}

	private static void amendThemeEntries(Map<String, ColorThemeSetting> theme) {
		// constants from ColorThemeKeys
		applyDefault(theme, ColorThemeKeys.METHOD, ColorThemeKeys.FOREGROUND);
		applyDefault(theme, ColorThemeKeys.FIELD, ColorThemeKeys.FOREGROUND);
		applyDefault(theme, ColorThemeKeys.LOCAL_VARIABLE,
				ColorThemeKeys.FOREGROUND);
		applyDefault(theme, ColorThemeKeys.JAVADOC,
				ColorThemeKeys.MULTI_LINE_COMMENT);
		applyDefault(theme, ColorThemeKeys.JAVADOC_LINK, ColorThemeKeys.JAVADOC);
		applyDefault(theme, ColorThemeKeys.JAVADOC_TAG, ColorThemeKeys.JAVADOC);
		applyDefault(theme, ColorThemeKeys.JAVADOC_KEYWORD,
				ColorThemeKeys.JAVADOC);
		applyDefault(theme, ColorThemeKeys.OCCURRENCE_INDICATION,
				ColorThemeKeys.BACKGROUND);
		applyDefault(theme, ColorThemeKeys.WRITE_OCCURRENCE_INDICATION,
				ColorThemeKeys.OCCURRENCE_INDICATION);
		applyDefault(theme, ColorThemeKeys.DEBUG_CURRENT_INSTRUCTION_POINTER,
				ColorThemeKeys.CURRENT_LINE);
		applyDefault(theme, ColorThemeKeys.DEBUG_SECONDARY_INSTRUCTION_POINTER,
				ColorThemeKeys.CURRENT_LINE);
		applyDefault(theme, ColorThemeKeys.CURRENT_LINE_IN_WIDGETS,
				ColorThemeKeys.CURRENT_LINE);
		applyDefault(theme, ColorThemeKeys.STDERR, ColorThemeKeys.KEYWORD);
		applyDefault(theme, ColorThemeKeys.STDIN, ColorThemeKeys.STRING);
		applyDefault(theme, ColorThemeKeys.STDOUT, ColorThemeKeys.FOREGROUND);

		// Compare editor
		applyDefault(theme, ColorThemeKeys.COMPARE_EDITOR_CONFLICTING_COLOR,
				ColorThemeKeys.STDERR);
		applyDefault(theme, ColorThemeKeys.COMPARE_EDITOR_RESOLVED_COLOR,
				ColorThemeKeys.STRING);
		applyDefault(theme, ColorThemeKeys.COMPARE_EDITOR_OUTGOING_COLOR,
				ColorThemeKeys.FOREGROUND);
		applyDefault(theme, ColorThemeKeys.COMPARE_EDITOR_INCOMING_COLOR,
				ColorThemeKeys.NUMBER);

		// Egit (VCS)
		applyDefault(theme, ColorThemeKeys.VCS_DIFF_ADD_FOREGROUND,
				ColorThemeKeys.COMPARE_EDITOR_INCOMING_COLOR);
		applyDefault(theme, ColorThemeKeys.VCS_DIFF_ADD_BACKGROUND,
				ColorThemeKeys.BACKGROUND);

		applyDefault(theme, ColorThemeKeys.VCS_DIFF_HEADLINE_FOREGROUND,
				ColorThemeKeys.FOREGROUND);
		applyDefault(theme, ColorThemeKeys.VCS_DIFF_HEADLINE_BACKGROUND,
				ColorThemeKeys.BACKGROUND);

		applyDefault(theme, ColorThemeKeys.VCS_DIFF_REMOVE_FOREGROUND,
				ColorThemeKeys.STDERR);
		applyDefault(theme, ColorThemeKeys.VCS_DIFF_REMOVE_BACKGROUND,
				ColorThemeKeys.BACKGROUND);

		applyDefault(theme, ColorThemeKeys.VCS_RESOURCE_IGNORED_FOREGROUND,
				ColorThemeKeys.FOREGROUND);
		applyDefault(theme, ColorThemeKeys.VCS_RESOURCE_IGNORED_BACKGROUND,
				ColorThemeKeys.BACKGROUND);

		applyDefault(theme, ColorThemeKeys.VCS_UNCOMMITED_CHANGE_FOREGROUND,
				ColorThemeKeys.FOREGROUND);
		applyDefault(theme, ColorThemeKeys.VCS_UNCOMMITED_CHANGE_BACKGROUND,
				ColorThemeKeys.BACKGROUND);

		applyDefault(theme, ColorThemeKeys.VCS_DIFF_HUNK_FOREGROUND,
				ColorThemeKeys.COMMENT_TASK_TAG);
		applyDefault(theme, ColorThemeKeys.VCS_DIFF_HUNK_BACKGROUND,
				ColorThemeKeys.BACKGROUND);

		applyDefault(theme, ColorThemeKeys.HYPERLINK, ColorThemeKeys.KEYWORD);
		applyDefault(theme, ColorThemeKeys.ACTIVE_HYPERLINK,
				ColorThemeKeys.KEYWORD);
		applyDefault(theme, ColorThemeKeys.MATCHING_BRACKET,
				ColorThemeKeys.OCCURRENCE_INDICATION);
		applyDefault(theme, ColorThemeKeys.SEARCH_VIEW_MATCH_HIGHLIGHT,
				ColorThemeKeys.OCCURRENCE_INDICATION);

		com.github.eclipsecolortheme.Color backgroundColor = theme.get(ColorThemeKeys.BACKGROUND).getColor();
		com.github.eclipsecolortheme.Color foregroundColor = theme.get(ColorThemeKeys.FOREGROUND).getColor();
		if(backgroundColor.isDarkColor()){
		    applyDefault(theme, ColorThemeKeys.SCROLL_BACKGROUND, backgroundColor.lighterRGB(.15));
		    applyDefault(theme, ColorThemeKeys.SCROLL_FOREGROUND, backgroundColor.lighterRGB(.30));
		    applyDefault(theme, ColorThemeKeys.SELECTED_TAB_BACKGROUND, backgroundColor.lighterRGB(.20));
		    applyDefault(theme, ColorThemeKeys.TREE_ARROWS_FOREGROUND, foregroundColor.darkerRGB(.20));
		}else{
			applyDefault(theme, ColorThemeKeys.SCROLL_BACKGROUND, backgroundColor.darkerRGB(.15));
			applyDefault(theme, ColorThemeKeys.SCROLL_FOREGROUND, backgroundColor.darkerRGB(.30));
			applyDefault(theme, ColorThemeKeys.SELECTED_TAB_BACKGROUND, backgroundColor.darkerRGB(.20));
			applyDefault(theme, ColorThemeKeys.TREE_ARROWS_FOREGROUND, foregroundColor.lighterRGB(.20));
		}
		
		TmThemeLoader.ammendTmEntries(theme);
		
		//Any key which doesn't have a default goes to the foreground color
		for (String string : ColorThemeKeys.ALL_KEYS) {
			applyDefault(theme, string, ColorThemeKeys.FOREGROUND);
		}
		
		com.github.eclipsecolortheme.Color fgColor = theme.get(ColorThemeKeys.FOREGROUND).getColor();
		Set<Entry<String, ColorThemeSetting>> entrySet = theme.entrySet();
		for (Entry<String, ColorThemeSetting> entry : entrySet) {
			ColorThemeSetting value = entry.getValue();
			if(value.getColor() == null){
			    value.setColor(fgColor);
			}
		}
	}

	private static void applyDefault(Map<String, ColorThemeSetting> theme,
			String key, RGB defaultColor) {
		if (!theme.containsKey(key)) {
			String asStr = new StringBuffer().append(defaultColor.red).append(",").append(defaultColor.green).append(",").append(defaultColor.blue).toString();
			ColorThemeSetting setting = new ColorThemeSetting(asStr);
			theme.put(key, setting);
		}
	}

	private static void applyDefault(Map<String, ColorThemeSetting> theme,
			String key, String defaultKey) {
		if (theme.get(key) == null) {
			theme.put(key, theme.get(defaultKey));
		}
	}
	/**
	 * Returns all available color themes.
	 *
	 * @return all available color themes.
	 */
	public Set<ColorTheme> getThemes() {
		return new HashSet<ColorTheme>(themes.values());
	}

	/**
	 * Returns the theme stored under the supplied name.
	 *
	 * @param name
	 *            The name of the theme.
	 * @return The requested theme or <code>null</code> if none was stored under
	 *         the supplied name.
	 */
	public ColorTheme getTheme(String name) {
		return themes.get(name);
	}

	/**
	 * Adds the color theme to the list and saves it to the preferences.
	 * Existing themes will be overwritten with the new content.
	 *
	 * @param content
	 *            The content of the color theme file.
	 * @return The saved color theme, or <code>null</code> if the theme was not
	 *         valid.
	 */
	public ColorTheme saveTheme(String content) {
		ColorTheme theme;
		try {
			theme = parseTheme(
					new ByteArrayInputStream(content.getBytes("utf-8")), false)
					.getTheme();
			saveTheme(content, theme);
			return theme;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Save the theme having the contents already parsed.
	 */
	private void saveTheme(String content, ColorTheme theme) {
		String name = theme.getName();
		themes.put(name, theme);
		IPreferenceStore store = getPreferenceStore();
		for (int i = 1;; i++) {
			String importedThemeId = "importedColorTheme" + i;
			if (!store.contains(importedThemeId)) {
				store.putValue(importedThemeId, content);
				theme.setImportedThemeId(importedThemeId);
				break;
			}
		}
	}

	public void removeTheme(ColorTheme theme) {
		try {
			String name = theme.getName();

			ColorTheme existingWithName = themes.get(name);

			if (existingWithName != null) {
				String importedThemeId = existingWithName.getImportedThemeId();
				if (importedThemeId != null) {
					themes.remove(name);
					IPreferenceStore store = getPreferenceStore();
					store.setToDefault(importedThemeId);
					theme.setImportedThemeId(importedThemeId);
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public ColorTheme saveEditedTheme(String content) {

		ColorTheme theme;
		try {
			theme = parseTheme(
					new ByteArrayInputStream(content.getBytes("utf-8")), false)
					.getTheme();
			String name = theme.getName();

			ColorTheme existingWithSameName = themes.get(name);

			if (existingWithSameName != null) {
				String importedThemeId = existingWithSameName
						.getImportedThemeId();
				if (importedThemeId != null) {
					// it's an existing theme in the preference store: we have
					// to save it overriding the old one that had the same name.
					themes.put(name, theme);
					IPreferenceStore store = getPreferenceStore();
					store.putValue(importedThemeId, content);
					theme.setImportedThemeId(importedThemeId);

					// When we edit a theme, we invalidate the
					// foreground/background caches as it may have changed.
					foregroundCache = null;
					backgroundCache = null;
					return theme;
				}
			}

			// if it got here, it's a new theme.
			saveTheme(content, theme);
			return theme;

		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public ColorTheme getCurrentTheme() {
		return getTheme(currentThemeName);
	}

	protected Map<com.github.eclipsecolortheme.Color, Color> cache = new HashMap<com.github.eclipsecolortheme.Color, Color>(
			10);

	public Color getColor(com.github.eclipsecolortheme.Color colorInTheme) {
		Color color = cache.get(colorInTheme);
		if (color == null || color.isDisposed()) {
			color = new Color(Display.getCurrent(), colorInTheme.getR(),
					colorInTheme.getG(), colorInTheme.getB());
			cache.put(colorInTheme, color);
		}
		return color;
	}

	/**
	 * Returns the SWT color to be used. Can be null if not available or no
	 * active theme is set.
	 */
	public org.eclipse.swt.graphics.Color getSWTColor(String key) {
		ColorTheme currentTheme = getCurrentTheme();
		if (currentTheme != null) {
			ColorThemeSetting themeSetting = currentTheme.getEntries().get(key);
			return getColor(themeSetting.getColor());
		}
		return null;
	}

	org.eclipse.swt.graphics.Color foregroundCache;
	org.eclipse.swt.graphics.Color backgroundCache;

	/**
	 * Returns the SWT color to be used for the foreground.
	 */
	public org.eclipse.swt.graphics.Color getSWTColorForeground() {
		if (foregroundCache == null || foregroundCache.isDisposed()) {
			foregroundCache = getSWTColor(ColorThemeKeys.FOREGROUND);
		}
		return foregroundCache;
	}

	/**
	 * Returns the SWT color to be used for the background.
	 */
	public org.eclipse.swt.graphics.Color getSWTColorBackground() {
		if (backgroundCache == null || backgroundCache.isDisposed()) {
			backgroundCache = getSWTColor(ColorThemeKeys.BACKGROUND);
		}
		return backgroundCache;
	}

	/**
	 * Adds the color theme to the list and saves it to the preferences.
	 * Existing themes will be overwritten with the new content.
	 *
	 * @param input
	 *            The input for theme file.
	 * @throws Exception 
	 */
	public void saveTheme(InputStream input)
			throws Exception {
		ParsedTheme theme = parseTheme(input, true);
		themes.put(theme.getTheme().getName(), theme.getTheme());
		IPreferenceStore store = getPreferenceStore();
		for (int i = 1;; i++)
			if (!store.contains("importedColorTheme" + i)) {
				store.putValue("importedColorTheme" + i, theme.getSource());
				break;
			}
	}

	protected static String documentToString(Document document)
			throws TransformerException {
		StringWriter writer = new StringWriter();
		Transformer transformer = TransformerFactory.newInstance()
				.newTransformer();
		transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
		transformer
				.transform(new DOMSource(document), new StreamResult(writer));
		return writer.toString();
	}
}
