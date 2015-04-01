/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.supaham.commons.yaml;

import com.google.common.base.Joiner;
import com.google.common.io.Files;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.reader.UnicodeReader;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import lombok.NonNull;
import pluginbase.config.ConfigSerializer;
import pluginbase.config.SerializationRegistrar;

/**
 * YAML configuration loader. To use this class, construct it with path to
 * a file and call its load() method. For specifying node paths in the
 * various get*() methods, they support SK's path notation, allowing you to
 * select child nodes by delimiting node names with periods.
 *
 * <p>
 * For example, given the following configuration file:</p>
 *
 * <pre>members:
 *     - Hollie
 *     - Jason
 *     - Bobo
 *     - Aya
 *     - Tetsu
 * worldguard:
 *     fire:
 *         spread: false
 *         blocks: [cloth, rock, glass]
 * sturmeh:
 *     cool: false
 *     eats:
 *         babies: true</pre>
 *
 * <p>Calling code could access sturmeh's baby eating state by using
 * {@code getBoolean("sturmeh.eats.babies", false)}. For lists, there are
 * methods such as {@code getStringList} that will return a type safe list.
 */
public class YAMLProcessor extends YAMLNode {

  public static final String LINE_BREAK = DumperOptions.LineBreak.getPlatformLineBreak()
      .getString();
  public static final char COMMENT_CHAR = '#';
  protected final Yaml yaml;
  private final DumperOptions options;
  protected final File file;
  protected String header = null;
  protected YAMLFormat format;

  /*
   * Map from property key to comment. Comment may have multiple lines that are newline-separated.
   * Comments support based on ZerothAngel's AnnotatedYAMLConfiguration
   * Comments are only supported with YAMLFormat.EXTENDED
   */
  private final Map<String, String> comments = new HashMap<String, String>();

  /**
   * This method returns an empty ConfigurationNode for using as a
   * default in methods that select a node from a node list.
   *
   * @param writeDefaults true to write default values when a property is requested that doesn't
   * exist
   *
   * @return a node
   */
  public static YAMLNode getEmptyNode(boolean writeDefaults) {
    return new YAMLNode(new LinkedHashMap<String, Object>(), writeDefaults);
  }


  /**
   * Constructs a new {@link YAMLProcessor} with {@code writeDefaults} as false and the
   * {@link YAMLFormat} as {@link YAMLFormat#COMPACT}.
   *
   * @param file file to base the {@link YAMLProcessor} instance from
   */
  public YAMLProcessor(File file) {
    this(file, false);
  }

  /**
   * Constructs a new {@link YAMLProcessor} with the {@link YAMLFormat} as
   * {@link YAMLFormat#COMPACT}.
   *
   * @param writeDefaults whether to write defaults to this {@link YAMLProcessor}
   * @param file file to base the {@link YAMLProcessor} instance from
   */
  public YAMLProcessor(File file, boolean writeDefaults) {
    this(file, writeDefaults, YAMLFormat.COMPACT);
  }

  /**
   * Constructs a new {@link YAMLProcessor} with {@code writeDefaults} as false.
   *
   * @param file file to base the {@link YAMLProcessor} instance from
   * @param format {@link YAMLFormat} to use with this configuration
   */
  public YAMLProcessor(File file, YAMLFormat format) {
    this(file, false, format);
  }

  /**
   * Constructs a new {@link YAMLProcessor}.
   *
   * @param file file to base the {@link YAMLProcessor} instance from
   * @param writeDefaults whether to write defaults to this {@link YAMLProcessor}
   * @param format {@link YAMLFormat} to use with this configuration
   */
  public YAMLProcessor(@NonNull File file, boolean writeDefaults, @NonNull YAMLFormat format) {
    super(new LinkedHashMap<String, Object>(), writeDefaults);
    this.file = file;
    this.format = format;

    this.options = new DumperOptions();
    this.options.setIndent(format == YAMLFormat.COMPACT ? 2 : 4);
    this.options.setDefaultFlowStyle(format.getStyle());
    Representer representer = new FancyRepresenter();
    representer.setDefaultFlowStyle(format.getStyle());

    this.yaml = new Yaml(new YamlConstructor(), representer, options);
  }

  /**
   * Loads the configuration file.
   *
   * @throws IOException on load error
   */
  public void load() throws IOException {
    InputStream stream = null;

    try {
      stream = getInputStream();
      if (stream == null) {
        throw new IOException("Stream is null!");
      }
      read(this.yaml.load(new UnicodeReader(stream)));
    } catch (YAMLProcessorException e) {
      this.root = new LinkedHashMap<>();
    } finally {
      try {
        if (stream != null) {
          stream.close();
        }
      } catch (IOException ignored) {
      }
    }
  }

  /**
   * Set the header for the file as a series of lines that are terminated
   * by a new line sequence.
   *
   * @param headerLines header lines to prepend
   */
  public void setHeader(String... headerLines) {
    StringBuilder header = new StringBuilder();

    for (String line : headerLines) {
      if (header.length() > 0) {
        header.append(LINE_BREAK);
      }
      header.append(line);
    }

    setHeader(header.toString());
  }

  /**
   * Set the header for the file. A header can be provided to prepend the
   * YAML data output on configuration save. The header is
   * printed raw and so must be manually commented if used. A new line will
   * be appended after the header, however, if a header is provided.
   *
   * @param header header to prepend
   */
  public void setHeader(String header) {
    this.header = header;
  }

  /**
   * Return the set header.
   *
   * @return the header text
   */
  public String getHeader() {
    return this.header;
  }

  /**
   * Saves the configuration to disk. All errors are clobbered.
   *
   * @return true if it was successful
   */
  public boolean save() {
    File parent = this.file.getParentFile();

    if (parent != null) {
      try {
        Files.createParentDirs(parent);
      } catch (IOException e) {
        e.printStackTrace();
        return false;
      }
    }

    try (OutputStream stream = getOutputStream()) {
      if (stream == null) {
        return false;
      }
      OutputStreamWriter writer = new OutputStreamWriter(stream, "UTF-8");
      if (this.header != null) {
        writer.append(this.header);
        writer.append(LINE_BREAK);
      }
      // TODO write comments n stuff.
      if (this.comments.isEmpty() || this.format != YAMLFormat.EXTENDED) {
        this.yaml.dump(this.root, writer);
      } else {
        // Iterate over each root-level property and dump
        for (Entry<String, Object> entry : this.root.entrySet()) {
          // Output comment, if present
          String comment = this.comments.get(entry.getKey());
          if (comment != null) {
            writer.append(LINE_BREAK);
            writer.append(comment);
            writer.append(LINE_BREAK);
          }

          // Dump property
          this.yaml.dump(Collections.singletonMap(entry.getKey(), entry.getValue()), writer);
        }
      }
      return true;
    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }

  private void read(Object input) throws YAMLProcessorException {
    try {
      if (null == input) {
        this.root = new LinkedHashMap<>();
      } else {
        this.root = new LinkedHashMap<>((Map<String, Object>) input);
      }
    } catch (ClassCastException e) {
      throw new YAMLProcessorException("Root document must be a key-value structure");
    }
  }

  public InputStream getInputStream() throws IOException {
    return new FileInputStream(this.file);
  }

  public OutputStream getOutputStream() throws IOException {
    return new FileOutputStream(this.file);
  }

  /**
   * Returns a root-level comment.
   *
   * @param key the property key
   * @return the comment or {@code null}
   */
  public String getComment(String key) {
    return comments.get(key);
  }

  public void setComment(String key, String comment) {
    if (comment != null) {
      setComment(key, comment.split("\\r?\\n"));
    } else {
      comments.remove(key);
    }
  }

  /**
   * Set a root-level comment.
   *
   * @param key the property key
   * @param comments the comment. May be {@code null}, in which case the comment
   *   is removed.
   */
  public void setComment(String key, String... comments) {
    if (comments != null && comments.length > 0) {
      for (int i = 0; i < comments.length; ++i) {
        if (!comments[i].matches("^" + COMMENT_CHAR + " ?")) {
          comments[i] = COMMENT_CHAR + " " + comments[i];
        }
      }
      String s = Joiner.on(LINE_BREAK).join(comments);
      this.comments.put(key, s);
    } else {
      this.comments.remove(key);
    }
  }

  /**
   * Returns root-level comments.
   *
   * @return map of root-level comments
   */
  public Map<String, String> getComments() {
    return Collections.unmodifiableMap(comments);
  }

  /**
   * Set root-level comments from a map.
   *
   * @param comments comment map
   */
  public void setComments(Map<String, String> comments) {
    this.comments.clear();
    if (comments != null) {
      this.comments.putAll(comments);
    }
  }

  public DumperOptions getOptions() {
    return options;
  }

  private static class FancyRepresenter extends Representer {

    private FancyRepresenter() {
      this.nullRepresenter = new Represent() {
        @Override
        public Node representData(Object o) {
          return representScalar(Tag.NULL, "");
        }
      };

      RepresentMap representer = new RepresentMap() {
        @Override
        public Node representData(Object data) {
          return super.representData(ConfigSerializer.serialize(data));
        }
      };
      for (Class clazz : SerializationRegistrar.getRegisteredClasses()) {
        this.multiRepresenters.put(clazz, representer);
      }
    }
  }
}
