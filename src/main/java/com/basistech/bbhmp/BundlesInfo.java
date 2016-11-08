/*
* Copyright 2016 Basis Technology Corp.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.basistech.bbhmp;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.XMLEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Represent the data from a bundle directory. This is very simple; with any luck
 * it will stay that way.
 * {@code
 *
 * <bundles>
    <level level="20">
      <bundle start="true">org.ow2.asm-asm-all-5.0.2.jar</bundle>
      <bundle start="true">org.apache.xbean-xbean-bundleutils-4.1.jar</bundle>
      <bundle start="true">org.apache.xbean-xbean-reflect-4.1.jar</bundle>
      <bundle start="true">org.apache.xbean-xbean-finder-4.1.jar</bundle>
      <bundle start="true">org.ow2.asm-asm-all-5.0.2.jar</bundle>
      <bundle start="true">org.apache.xbean-xbean-bundleutils-4.1.jar</bundle>
      <bundle start="true">org.apache.xbean-xbean-reflect-4.1.jar</bundle>
      <bundle start="true">org.apache.xbean-xbean-finder-4.1.jar</bundle>
     </level>
    </bundles>
  * }
 */
class BundlesInfo {
    final List<LevelBundles> levels;

    BundlesInfo(List<LevelBundles> levels) {
        this.levels = levels;
    }

    static BundlesInfo read(Path xmlFile) throws IOException {
        List<LevelBundles> levels = new ArrayList<>();
        XMLStreamReader reader;
        try (InputStream is = Files.newInputStream(xmlFile)) {
            reader = XMLInputFactory.newFactory().createXMLStreamReader(is);
            reader.nextTag();
            reader.require(XMLEvent.START_ELEMENT, null, "bundles");
            while (reader.nextTag() == XMLEvent.START_ELEMENT) {
                reader.require(XMLEvent.START_ELEMENT, null, "level");
                int level = Integer.parseInt(reader.getAttributeValue(null, "level"));
                List<BundleInfo> bundleInfos = new ArrayList<>();
                while (reader.nextTag() == XMLEvent.START_ELEMENT) {
                    reader.require(XMLEvent.START_ELEMENT, null, "bundle");
                    boolean start = Boolean.parseBoolean(reader.getAttributeValue(null, "start"));
                    reader.next();
                    if (!reader.isCharacters()) {
                        throw new IOException("No bundle filename text at " + reader.getLocation().toString());
                    }
                    String filename = reader.getText();
                    BundleInfo bundleInfo = new BundleInfo(start, filename);
                    bundleInfos.add(bundleInfo);
                    reader.nextTag();
                    reader.require(XMLEvent.END_ELEMENT, null, "bundle");

                }
                // end of this loop means that we've hit the end element for the level.
                reader.require(XMLEvent.END_ELEMENT, null, "level");
                LevelBundles levelBundles = new LevelBundles(level, bundleInfos);
                levels.add(levelBundles);
            }
            // hit the end of the whole business.
            reader.require(XMLEvent.END_ELEMENT, null, "bundles");
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
        levels.sort(Comparator.comparingInt(o -> o.level));
        return new BundlesInfo(levels);
    }
}
