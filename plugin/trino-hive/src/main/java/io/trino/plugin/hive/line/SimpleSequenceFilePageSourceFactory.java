/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.hive.line;

import com.google.inject.Inject;
import io.trino.filesystem.TrinoFileSystemFactory;
import io.trino.hive.formats.line.sequence.SequenceFileReaderFactory;
import io.trino.hive.formats.line.simple.SimpleDeserializerFactory;
import io.trino.plugin.hive.HiveConfig;
import io.trino.plugin.hive.HiveSessionProperties;

import static java.lang.Math.toIntExact;

public class SimpleSequenceFilePageSourceFactory
        extends LinePageSourceFactory
{
    @Inject
    public SimpleSequenceFilePageSourceFactory(TrinoFileSystemFactory trinoFileSystemFactory, HiveConfig config)
    {
        super(trinoFileSystemFactory,
                new SimpleDeserializerFactory(),
                new SequenceFileReaderFactory(1024, toIntExact(config.getTextMaxLineLength().toBytes())),
                HiveSessionProperties::isSequenceFileNativeReaderEnabled);
    }
}
