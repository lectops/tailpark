// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package parkedactivity

import (
	"encoding/binary"
	"tailscale.com/net/packet"
	"testing"
	"time"
)

func tcp(srcPort, dstPort uint16, payload int, ack uint32, flags byte) *packet.Parsed {
	b := make([]byte, 40+payload)
	b[0], b[9] = 0x45, 6
	binary.BigEndian.PutUint16(b[2:4], uint16(len(b)))
	copy(b[12:16], []byte{100, 64, 0, 1})
	copy(b[16:20], []byte{100, 64, 0, 2})
	binary.BigEndian.PutUint16(b[20:22], srcPort)
	binary.BigEndian.PutUint16(b[22:24], dstPort)
	binary.BigEndian.PutUint32(b[28:32], ack)
	b[32], b[33] = 0x50, flags
	p := new(packet.Parsed)
	p.Decode(b)
	return p
}

func udp(srcPort, dstPort uint16, payload int) *packet.Parsed {
	b := make([]byte, 28+payload)
	b[0], b[9] = 0x45, 17
	binary.BigEndian.PutUint16(b[2:4], uint16(len(b)))
	copy(b[12:16], []byte{100, 64, 0, 1})
	copy(b[16:20], []byte{100, 64, 0, 2})
	binary.BigEndian.PutUint16(b[20:22], srcPort)
	binary.BigEndian.PutUint16(b[22:24], dstPort)
	binary.BigEndian.PutUint16(b[24:26], uint16(8+payload))
	p := new(packet.Parsed)
	p.Decode(b)
	return p
}

func enabled(t *testing.T) (*Tracker, time.Time) {
	t.Helper()
	tracker := new(Tracker)
	start := time.Unix(0, 0)
	if tracker.IdleMillis(start) != -1 {
		t.Fatal("unready tracker confirmed idle")
	}
	tracker.Ready()
	tracker.Enable(true, start)
	return tracker, start
}

func TestActiveMultiHourOutboundDownload(t *testing.T) {
	tracker, start := enabled(t)
	for second := 1; second <= 4*60*60; second++ {
		now := start.Add(time.Duration(second) * time.Second)
		tracker.Observe(tcp(8080, 50000, 1400, 0, 0x10), false, now)
		if got := tracker.IdleMillis(now); got != 0 {
			t.Fatalf("stream idle at %d: %d", second, got)
		}
	}
}

func TestNetstackOutboundAckProgress(t *testing.T) {
	tracker, start := enabled(t)
	tracker.Observe(tcp(50000, 8080, 100, 1000, 0x10), true, start) // inbound HTTP request
	now := start.Add(time.Second)
	tracker.Observe(tcp(50000, 8080, 0, 64000, 0x10), true, now) // receiver ACKs outbound data
	if tracker.IdleMillis(now) != 0 {
		t.Fatal("outbound userspace stream did not renew")
	}
	later := now.Add(time.Hour)
	tracker.Observe(tcp(50000, 8080, 0, 64001, 0x10), true, later)
	if tracker.IdleMillis(later) < 3600000 {
		t.Fatal("idle ACK/keepalive renewed")
	}
}

func TestKeepalivesScansAndDNSDoNotRenew(t *testing.T) {
	tracker, start := enabled(t)
	for second := 1; second < 7200; second++ {
		now := start.Add(time.Duration(second) * time.Second)
		tracker.Observe(tcp(50000, 8080, 0, uint32(second*10000), 0x10), true, now) // no established application stream
		tracker.Observe(tcp(50001, 8080, 0, 0, 0x02), true, now)                    // SYN probe
		tracker.Observe(tcp(50002, 8080, 1, 1, 0x10), true, now)                    // one-byte keepalive
		tracker.Observe(udp(50003, 53, 1400), true, now)
		tracker.Observe(udp(50003, 8080, 32), true, now)
	}
	if tracker.IdleMillis(start.Add(2*time.Hour)) != 7200000 {
		t.Fatal("routine traffic renewed idle window")
	}
}

func TestDebounceAndUnavailableReporter(t *testing.T) {
	tracker, start := enabled(t)
	tracker.Observe(tcp(40000, 22, 500, 0, 0x10), true, start.Add(time.Second))
	tracker.Observe(tcp(40000, 22, 500, 0, 0x10), true, start.Add(20*time.Second))
	if tracker.IdleMillis(start.Add(20*time.Second)) != 20000 {
		t.Fatal("slow telemetry crossed window")
	}
	tracker.Observe(udp(40000, 9999, 1400), false, start.Add(21*time.Second))
	if tracker.IdleMillis(start.Add(21*time.Second)) != 0 {
		t.Fatal("UDP data ignored")
	}
	tracker.Enable(false, start.Add(time.Minute))
	if tracker.IdleMillis(start.Add(time.Hour)) != -1 {
		t.Fatal("disabled tracker claimed idle")
	}
	tracker.Enable(true, start.Add(time.Hour))
	if tracker.IdleMillis(start.Add(time.Hour)) != 0 {
		t.Fatal("reenabling did not grant conservative window")
	}
}

func TestBoundedFlowMetadata(t *testing.T) {
	tracker, start := enabled(t)
	for port := 1024; port < 4096; port++ {
		tracker.Observe(tcp(uint16(port), 8080, 2, 0, 0x10), true, start)
	}
	if len(tracker.flows) > 256 {
		t.Fatal("unbounded connection metadata")
	}
}

func TestEnableDuringExistingNetstackTransfer(t *testing.T) {
	tracker := new(Tracker)
	tracker.Ready()
	start := time.Unix(0, 0)
	tracker.Observe(tcp(50000, 8080, 100, 1000, 0x10), true, start)
	tracker.Observe(tcp(50000, 8080, 0, 64000, 0x10), true, start.Add(time.Second))
	tracker.Enable(true, start.Add(2*time.Second))
	tracker.Observe(tcp(50000, 8080, 0, 128000, 0x10), true, start.Add(3*time.Second))
	if tracker.IdleMillis(start.Add(3*time.Second)) != 0 {
		t.Fatal("enabling lost existing stream metadata")
	}
}

func TestActiveNetstackStreamSurvivesFlowChurn(t *testing.T) {
	tracker, start := enabled(t)
	tracker.Observe(tcp(50000, 8080, 100, 1000, 0x10), true, start)
	ack := uint32(1000)
	for second := 1; second <= 1000; second++ {
		now := start.Add(time.Duration(second) * time.Second)
		ack += 64000
		tracker.Observe(tcp(50000, 8080, 0, ack, 0x10), true, now)
		for j := 0; j < 20; j++ {
			tracker.Observe(tcp(uint16(1024+(second*20+j)%40000), 8081, 2, 0, 0x10), true, now.Add(time.Millisecond))
		}
		tracker.Observe(tcp(50000, 8080, 0, ack+64000, 0x10), true, now.Add(2*time.Millisecond))
		ack += 64000
		if tracker.IdleMillis(now.Add(2*time.Millisecond)) != 0 {
			t.Fatalf("active stream lost at %d with %d flows", second, len(tracker.flows))
		}
	}
}

func TestBurstOverflowNeverClaimsEvictedTransferIsIdle(t *testing.T) {
	tracker, start := enabled(t)
	tracker.Observe(tcp(50000, 8080, 100, 1000, 0x10), true, start)
	for port := 1024; port < 1400; port++ {
		tracker.Observe(tcp(uint16(port), 8081, 2, 0, 0x10), true, start.Add(time.Second))
	}
	if tracker.IdleMillis(start.Add(4*time.Hour)) != -1 {
		t.Fatal("overloaded reporter inferred idle after evicting active stream")
	}
	tracker.Enable(false, start.Add(4*time.Hour))
	tracker.Enable(true, start.Add(4*time.Hour))
	if tracker.IdleMillis(start.Add(4*time.Hour)) != 0 {
		t.Fatal("explicit tracking reset did not recover")
	}
}
