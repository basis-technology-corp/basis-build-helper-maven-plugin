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

import java.util.Objects;

/**
 * A specification of a single bundle as read from an input XML file.
 */
class BundleSpec {
    final String gav;
    final int level;
    final boolean start; // we will calculate as needed.
    final String filename;

    BundleSpec(String gav, int level, boolean start, String filename) {
        this.gav = gav;
        this.level = level;
        this.start = start;
        this.filename = filename;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BundleSpec that = (BundleSpec) o;
        return level == that.level
                && start == that.start
                && Objects.equals(gav, that.gav)
                && Objects.equals(filename, that.filename);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gav, level, start, filename);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("BundleSpec{");
        sb.append("gav='").append(gav).append('\'');
        sb.append(", level=").append(level);
        sb.append(", start=").append(start);
        sb.append(", filename=").append(filename);
        sb.append('}');
        return sb.toString();
    }
}
