// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause

// Package parkedactivity measures application data, never encrypted VPN/control bytes.
package parkedactivity

import (
	"encoding/binary"
	"net/netip"
	"sync"
	"time"

	"tailscale.com/net/packet"
	"tailscale.com/types/ipproto"
)

const threshold = 1024
const window = 10 * time.Second

type flow struct{ src, dst netip.AddrPort }
type progress struct {
	ack   uint32
	at    time.Time
	valid bool
}

// Tracker stores bounded, ephemeral transport metadata. Payloads and addresses are
// never exported or written to disk. Bounded stream metadata remains observed
// while counting is disabled so enabling a timer during a transfer is safe.
// All accepted peers/services count except DNS.
// >=1 KiB of TCP payload/acknowledged stream data or UDP payload (packets >=128 B)
// within ten seconds renews activity. This intentionally ignores quiet sockets,
// TCP keepalives, small probes, DNS, ICMP, TSMP, and WireGuard/control-plane traffic.
type Tracker struct {
	overloaded     bool
	mu             sync.Mutex
	enabled        bool
	ready          bool
	last, bucketAt time.Time
	bytes          int
	flows          map[flow]progress
}

func (t *Tracker) Ready() { t.mu.Lock(); defer t.mu.Unlock(); t.ready = true }

func (t *Tracker) Enable(enabled bool, now time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.enabled == enabled {
		return
	}
	t.enabled = enabled
	t.overloaded = false
	t.last, t.bucketAt = now, now
	t.bytes = 0
}

// IdleMillis returns -1 when observation is unavailable. It never waits for
// backend startup, so an app cold-start cannot accidentally confirm expiry.
func (t *Tracker) IdleMillis(now time.Time) int64 {
	t.mu.Lock()
	defer t.mu.Unlock()
	if !t.ready || !t.enabled || t.overloaded {
		return -1
	}
	return max(0, now.Sub(t.last).Milliseconds())
}

func (t *Tracker) Observe(p *packet.Parsed, inbound bool, now time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if p.Src.Port() == 53 || p.Dst.Port() == 53 || p.Src.Port() == 5353 || p.Dst.Port() == 5353 {
		return
	}
	payload := len(p.Payload())
	n := 0
	switch p.IPProto {
	case ipproto.TCP:
		if payload > 1 {
			n = payload
		} // one-byte TCP keepalive probes are not activity
		if inbound {
			tr := p.Transport()
			if len(tr) >= 20 {
				k := flow{p.Src, p.Dst}
				prev := t.flows[k]
				ack := binary.BigEndian.Uint32(tr[8:12])
				// Advancing ACKs protect outbound userspace/netstack streams, whose
				// writes bypass Android's TUN. Only a flow with observed application
				// payload can establish this evidence; SYN/ACK scans cannot.
				if prev.valid && now.Sub(prev.at) < 2*time.Minute && p.TCPFlags&packet.TCPAck != 0 {
					delta := int32(ack - prev.ack)
					if delta > 1 && delta <= 16*1024*1024 {
						n += int(delta)
					}
				}
				if payload > 1 || prev.valid && now.Sub(prev.at) < 2*time.Minute {
					if t.flows == nil {
						t.flows = make(map[flow]progress)
					}
					if _, exists := t.flows[k]; !exists && len(t.flows) >= 256 {
						var oldest flow
						var oldestAt time.Time
						for key, entry := range t.flows {
							if oldestAt.IsZero() || entry.at.Before(oldestAt) {
								oldest, oldestAt = key, entry.at
							}
						}
						// Losing recently observed stream evidence under a burst
						// must never be interpreted as proof of idle later.
						if t.enabled && now.Sub(oldestAt) < window {
							t.overloaded = true
						}
						delete(t.flows, oldest)
					}
					t.flows[k] = progress{ack, now, true}
				}
				if p.TCPFlags&(packet.TCPFin|packet.TCPRst) != 0 {
					delete(t.flows, k)
				}
			}
		}
	case ipproto.UDP:
		if payload >= 128 {
			n = payload
		}
	}
	if !t.enabled || n == 0 {
		return
	}
	if now.Sub(t.bucketAt) > window {
		t.bucketAt, t.bytes = now, 0
	}
	t.bytes += n
	if t.bytes >= threshold {
		t.last, t.bucketAt, t.bytes = now, now, 0
	}
}
