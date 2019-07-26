/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.resolver;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class HostsFileParserTest {

	@Test
	public void testParse() throws IOException {
		String hostsString = "127.0.0.1 host1" + "\n" // single hostname, separated with blanks
				+ "\n" // empty line
				+ "192.168.0.1\thost2" + "\n" // single hostname, separated with tabs
				+ "#comment" + "\n" // comment at the beginning of the line
				+ " #comment  " + "\n" // comment in the middle of the line
				+ "192.168.0.2  host3  #comment" + "\n" // comment after hostname
				+ "192.168.0.3  host4  host5 host6" + "\n" // multiple aliases
				+ "192.168.0.4  host4" + "\n" // host mapped to a second address, must be ignored
				;

		Map<String, InetAddress> entries = HostsFileParser.parse(new BufferedReader(new StringReader(hostsString)));

		assertEquals("Expected 6 entries", 6, entries.size());
		assertEquals("127.0.0.1", entries.get("host1").getHostAddress());
		assertEquals("192.168.0.1", entries.get("host2").getHostAddress());
		assertEquals("192.168.0.2", entries.get("host3").getHostAddress());
		assertEquals("192.168.0.3", entries.get("host4").getHostAddress());
		assertEquals("192.168.0.3", entries.get("host5").getHostAddress());
		assertEquals("192.168.0.3", entries.get("host6").getHostAddress());
	}
}
