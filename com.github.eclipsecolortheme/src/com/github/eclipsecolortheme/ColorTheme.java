package com.github.eclipsecolortheme;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.swt.graphics.FontData;

public class ColorTheme {
	
    private String id;
    private String name;
    private String author;
    private String website;
    private Map<String, ColorThemeSetting> entries;
    private Map<String, Map<String, ColorThemeMapping>> mappings;
	/**
	 * This is the id of the theme in the preferences store. If a theme was not
	 * loaded from the preferences store, the importedThemeId is null.
	 */
	private String importedThemeId;
	
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public Map<String, ColorThemeSetting> getEntries() {
        return entries;
    }
    
	/**
	 * This is the id of the theme in the preferences store. If a theme was not
	 * loaded from the preferences store, the importedThemeId is null.
	 */
	public void setImportedThemeId(String importedThemeId) {
		this.importedThemeId = importedThemeId;
	}

	public String getImportedThemeId() {
		return importedThemeId;
	}

    public void setEntries(Map<String, ColorThemeSetting> entries) {
        this.entries = entries;
    }

    public Map<String, Map<String, ColorThemeMapping>> getMappings() {
        return mappings;
    }

    public void setMappings(Map<String, Map<String, ColorThemeMapping>> mappings) {
        this.mappings = mappings;
    }


	/**
	 * @param key
	 *            the entry (i.e.: "background")
	 * @return
	 */
	public boolean isDarkColor(String key) {
		ColorThemeSetting colorThemeSetting = entries.get(key);
		Color color = colorThemeSetting.getColor();
		return color.isDarkColor();
	}

	public ColorTheme createCopy() {
		ColorTheme copy = new ColorTheme();
		copy.id = id;
		copy.name = name;
		copy.author = author;
		copy.website = website;
		HashMap<String, ColorThemeSetting> map = new HashMap<String, ColorThemeSetting>();
		Set<Entry<String, ColorThemeSetting>> entrySet = entries.entrySet();
		for (Entry<String, ColorThemeSetting> entry : entrySet) {
			map.put(entry.getKey(), entry.getValue().createCopy());
		}
		copy.entries = map;
		return copy;
	}

	@SuppressWarnings("nls")
	public String toXML() {
		StringBuffer buf = new StringBuffer();
		Calendar calendar = Calendar.getInstance();

		String modified = calendar.get(Calendar.YEAR) + 1900 + "-"
				+ calendar.get(Calendar.MONTH) + "-"
				+ calendar.get(Calendar.DAY_OF_MONTH) + " "
				+ calendar.get(Calendar.HOUR_OF_DAY) + ":"
				+ calendar.get(Calendar.MINUTE) + ":"
				+ calendar.get(Calendar.SECOND);

		buf.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
		buf.append("<colorTheme id=\"1\" name=\"" + name + "\" modified=\""
				+ modified + "\" author=\"" + author + "\" website=\""
				+ website + "\">\n");
		Map<String, ColorThemeSetting> entries2 = this.getEntries();
		Set<Entry<String, ColorThemeSetting>> entrySet = entries2.entrySet();
		for (Entry<String, ColorThemeSetting> entry : entrySet) {
			ColorThemeSetting setting = entry.getValue();
			buf.append("    <" + entry.getKey() + " color=\""
					+ setting.getColor().asHex() + "\" ");

			buf.append(" bold=").append('"').append(setting.isBoldEnabled())
					.append('"');
			buf.append(" underline=").append('"')
					.append(setting.isUnderlineEnabled()).append('"');
			buf.append(" strikethrough=").append('"')
					.append(setting.isStrikethroughEnabled()).append('"');
			buf.append(" italic=").append('"')
					.append(setting.isItalicEnabled()).append('"');
			
			buf.append(" useCustomBackground=").append('"')
				.append(setting.useCustomBackground()).append('"');
			
			buf.append(" useCustomFont=").append('"')
				.append(setting.useCustomFont()).append('"');
			
			Color backgroundColor = setting.getBackgroundColor();
			if(backgroundColor != null){
				buf.append(" backgroundColor=\"").append(backgroundColor.asHex()).append("\" ");
			}
			
			FontData font = setting.getFont();
			if(font != null){
				buf.append(" font=\"").append(StringEscapeUtils.escapeXml(ColorThemeSetting.fontToString(font))).append("\" ");
			}
			
			buf.append("/>\n");
		}
		buf.append("</colorTheme>\n");
		return buf.toString();
	}

}
