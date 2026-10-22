---
title: Swarm
description: Peer identity, bootstrap, gossip membership, failure detection, SSH messaging, foreground attach, work delegation, and swarm security.
---

# Swarm

Swarm code is in `jeorje-swarm`. It uses the `Transport` port from `jeorje-core`. Each instance is a peer. There is no central server. The class `SwarmNode` is one peer. The class `SwarmBridge` in `jeorje-cli` connects it to the agents of the instance. The send target of the transport is the `host:port` address of a peer.

## Peer identity

Each instance has a `PeerId`. It is a random UUID made at first start. The instance saves it in the file `${HOME}/.jeorje/peer-id`. A peer record has these values:

- `peerId`, a `host:port` address, and a display name,
- an `incarnation` number,
- the state of the peer,
- a list of capabilities. The program sets `tools:<name>` for each tool that a delegated task can use (for example `tools:bash`),
- a workspace label. The program does not set it now,
- the `host:port` address of the SSH server of the peer. The value is blank when SSH is off.

The display name is the name of the working directory.

The saved id can be in use by another live instance of the same user. The instance checks the discovery lists at start. If another instance uses the saved id, the new instance makes a new random id and saves it.

## Bootstrap

The swarm is on by default. The default TCP bind address is `127.0.0.1:0`, which means a free port. An instance joins the swarm in one of three ways:

1. **WKA list.** The user gives a list of well-known addresses with `--peer host:port` or with `swarm.wka` in the config. The instance contacts these addresses and starts the gossip exchange. It contacts them again in each round as long as it knows no reachable peer.
2. **Local rendezvous.** Instances that share one user directory find each other through files. See [Local rendezvous](#local-rendezvous).
3. **Gossip only.** An instance with no WKA list waits. Other peers that know its address can contact it. After the first contact, the instance learns the full membership by gossip.

Zeroconf discovery is optional. The config key `swarm.discovery` (default `false`) turns it on. The class `ZeroconfDiscovery` uses multicast DNS and DNS-SD (the JmDNS library). It registers the service type `_jeorje._tcp.local.` for this instance. The service name is the peer id. The text record has the keys `peerId`, `address`, `sshAddress`, and `name`. The class also browses the same service type. Other instances on the network are extra WKA entries: the swarm contacts them when it knows no reachable peer. If the bind address of an instance is a wildcard, the resolved host address of the service replaces it. At start, Jeorje waits up to 500 ms for the first answers. If zeroconf cannot start (for example, the network has no multicast), Jeorje logs a warning and the swarm runs without discovery. Zeroconf only finds peers. Gossip still spreads the membership and the failure state after the first contact. The interface `PeerDiscovery` hides the discovery method from `SwarmNode`.

### Local rendezvous

The config key `swarm.rendezvous` (default `true`) turns on the class `LocalRendezvous`. It needs no multicast network. Each instance writes the file `${HOME}/.jeorje/swarm/rendezvous/<peerId>.json`. The file has the peer id, the TCP address, the SSH address, the name, the process id, and the time of the last refresh. The instance refreshes the file every 10 seconds and deletes it when it stops. An instance lists the files of the other instances. It skips a file that is older than 30 seconds and a file whose process is not alive. The class `CompositeDiscovery` joins the rendezvous and zeroconf lists and keeps one entry for each peer id.

## Transport

The transport is TCP (`TcpTransport`). Each message is one JSON object with a 4-byte length prefix. Jackson 3 reads and writes the JSON. Each peer keeps one long-lived outbound connection to each peer it sends to. A new connection starts with a handshake. The handshake sends the protocol version and proves the shared token. See [Security](#security). A peer sends all messages to one address in order. If the configured port is in use, the program takes a free port on the same host. TCP carries gossip, membership, failure detection, delegation, and history queries. SSH carries the traffic between agents. See [SSH channel](#ssh-channel).

## Membership protocol

The protocol follows the SWIM design.

- **Gossip round.** Each instance picks one random reachable peer in each round (`swarm.gossipIntervalMs`, default 1 second). The instance sends a digest of its membership table. The peer answers with the records that differ and a list of the peers for which it wants the full record. A peer that changes state also sends the new record to a few random peers.
- **Failure detection.** Each instance sends a `ping` to one random reachable peer in each round. If no `ack` comes in 500 ms, the instance asks 3 other peers to send an indirect ping (`ping-req`). If no ack comes in a further 500 ms, the instance marks the peer `SUSPECT`.
- **States.** A peer is `ALIVE`, `SUSPECT`, `DEAD`, or `LEFT`. A `SUSPECT` peer becomes `DEAD` after 10 s with no refutation. A peer that shuts down sends `LEFT`.
- **Refutation.** A peer that hears that it is `SUSPECT` or `DEAD` raises its `incarnation` number above the number in the accusation and sends an `ALIVE` record. A record with a higher incarnation replaces a record with a lower one. At the same incarnation, the state with the higher severity wins (`ALIVE`, `SUSPECT`, `DEAD`, `LEFT`).
- **Dead records.** Jeorje keeps `DEAD` and `LEFT` records for 5 minutes. This stops old gossip from bringing the peer back.
- **Healing a partition.** In some rounds, a peer also sends a gossip request to a `DEAD` peer. If the peer is alive again, it refutes the record, and the swarm becomes whole again.

The membership table has one owner thread. Other threads read an immutable snapshot.

## Messages

| Message | Purpose |
|---------|---------|
| `gossip` | Membership digest and records |
| `ping`, `ack`, `ping-req` | Failure detection |
| `agent_message` | A message to a peer. The field `kind` selects the use: `text` (text for an agent, SSH only), `ask`, `answer`, and `answer_error` (questions and answers of a delegated task), `steer` (steering for a delegated task), and `cancel` (stop a delegated task) |
| `agent_ask`, `agent_answer`, `agent_error` | A question from an agent to an agent on a peer, the answer, and the reason when no answer can come. The messages carry a request id. SSH only. |
| `agent_roster` | A request for the agents of a peer, and the reply. SSH only. |
| `agent_attach`, `agent_line`, `agent_steer`, `agent_answer_req`, `agent_answer_reply` | The foreground attach. SSH only. See [Foreground attach](#foreground-attach). |
| `delegate` | Request that a peer run a task in a remote subagent |
| `delegate_event` | Progress, questions, and results from a remote subagent |
| `history_query` | Request to search the history of a remote agent. The field `kind` is `request` or `result`. |

Each message has a protocol version, a sender `PeerId`, the sender address, a message id, and a time. A peer ignores an unknown message type and logs it. A peer also ignores an SSH-only message that comes over TCP, and logs it. A peer drops a message with a wrong protocol version, or with a sender that is not the peer of the connection.

The swarm node runs all callbacks to the agents of the instance on one serial executor, in message order. A callback must not block for a long time.

## SSH channel

Each instance runs an SSH server (Apache MINA SSHD, class `SwarmSshServer`) when `swarm.ssh.enabled` is `true` (the default). The server binds to `swarm.ssh.bind` (default `127.0.0.1:0`, a free port). The key `swarm.ssh.advertiseHost` sets the host that peers dial. An empty value means the host of the bind address. The instance publishes the real `host:port` as `sshAddress` in its peer record, in the gossip digests, and in the discovery entries. A peer learns the SSH address of a peer that it never contacted.

**Ephemeral keys.** The host key is an RSA key with 3072 bits. Jeorje makes it in memory at each start. Jeorje never writes it to a file. The client accepts any host key. There is no known-hosts file and no key pinning.

**Token check.** The only trust check is the password. The server accepts a password that is equal to the swarm token. It rejects every other password, including an empty one. The swarm token is the key `swarm.token`. If the key is empty, Jeorje reads the file `${HOME}/.jeorje/swarm-token`. If the file is missing, Jeorje writes a random 32-byte token (in hex) to it, readable by the owner only. A token that you set always wins. All instances that share one user directory share one token. The SSH user name is the peer id of the client. The server accepts only the command `jeorje-swarm`. It ends any other command with exit status 1.

**Frames.** The command channel carries frames with the same format as TCP: a 4-byte length and one JSON object. Each peer opens its own outbound connection to the peers that it sends to. A reply travels on the connection that the replying peer opens. The sender finds the SSH address of a target in the membership table. A send to an unknown peer, or to a peer with no SSH address, fails at once.

**Agent traffic.** The tools `send_message` and `ask_peer` use SSH. A text message goes to the agent of the user of the peer as an input from the peer. A question has a request id. The peer answers with `agent_answer`, or with `agent_error` and a reason. A question ends with the reason when the peer does not answer in 30 seconds, or when the peer is lost. A message or a free-form question never approves a tool run on the peer. If the peer has no direct answerer, the question goes to its agent as a message, and the asking agent gets an error that says so.

## Foreground attach

The local user can bring an agent of a peer into the foreground. The host is the peer that runs the agent. The client is the instance of the user. All frames use the SSH channel.

1. The client sends `agent_roster` to list the agents of the host. The host answers with the agent id, the name, the parent id, and the state of each agent.
2. The client sends `agent_attach` with the kind `open` and the agent id. If the host has no such agent, it answers with the kind `error` and no attachment starts. Otherwise it answers with the kind `opened` and the history lines of the agent.
3. The host sends `agent_line` for each new output line of the agent, in order.
4. The client sends `agent_steer` with text. The host treats the text like text that the user types for the agent.
5. When the agent asks for a permission, the live lines name the request id. The client sends `agent_answer_req` with the request id and the answer (`yes` or `no`). The host answers with `agent_answer_reply`.
6. The client sends `agent_attach` with the kind `close` to detach. The host stops the lines. If the client is lost, the host detaches by itself. Lines that arrive at the client after the close are dropped.

The remote user has the same rights over the attached agent as the local user. The token is the only check. The host shows a notice when a peer attaches and when it detaches.

## Delegation

The `spawn_agent` tool can target a peer. The tool takes a `peer` parameter (an id, a name, or an address), or a capability filter such as `capability: "tools:bash"`. Only a peer in the state `ALIVE` can be the target.

1. The local agent sends `delegate` with the task id, the task text, the allowed tool list, and the limits (`maxTurns` and `timeoutSeconds`). The limits come from the local subagent limits. The local instance ends the remote subagent with the outcome `failed` when the time limit passes. The peer runs the task under its own subagent limits.
2. The peer asks its own permission policy for `spawn_agent` and for each allowed tool. The peer then starts a subagent. If the policy denies one of them, the peer returns a rejection (`delegate_event` with the outcome `rejected`).
3. The remote subagent sends `delegate_event` messages. Each event has a number. The last one has the outcome and the completion summary, and the local instance writes it to its history as a `subagent_summary` chunk. If the peer is lost (`DEAD` or `LEFT`) before the last event, the local subagent ends with the outcome `failed`. The local instance shows the events as a local subagent. See [subagents.md](subagents.md).
4. The user can switch into the remote subagent and steer it. Steering sends `agent_message` with the kind `steer` to the peer. The peer accepts `steer`, `cancel`, and `answer` messages only from the peer that started the task.
5. When the task ends, the remote subagent sends its final result. The result goes to the local agent as a tool result (in `wait` mode) or as an input event (in `background` mode). If the delegating peer is lost, the peer cancels the task.

The peer runs the task in its own workspace with its own permissions. If the peer runs with `--yolo`, it runs the task with no permission checks. The local instance does not send files. If the task needs files, the delegating agent includes them in the task text or the peers share a file system.

## Remote history queries

A peer can ask another peer to search the history of one of its agents with `history_query`. The remote peer runs a `HistoryQuery` on its `ContextStore` and returns the hits. This is read-only. The remote user rules decide if it is allowed: only a user permission rule with `ALLOW` for the tool `history_query` and the peer allows it. The default is `DENY`. A denied query returns an empty result with `allowed` set to false. The hits have the agent id, a snippet, and a score.

## Security

A shared token protects the swarm.

- All peers in a swarm have the same token. It comes from `JEORJE_SWARM_TOKEN`, from `--swarm-token`, from `swarm.token` in the config, or from the file `${HOME}/.jeorje/swarm-token`. See [SSH channel](#ssh-channel).
- Both sides of a new connection send a random nonce. Each side proves the token with an HMAC-SHA256 over its role (`client` or `server`), both nonces, and its own peer id. The key is a SHA-256 hash of the token. Jeorje never sends the token over the network. A side that fails the proof gets a `reject` frame, and the connection closes.
- The swarm always has a token, because Jeorje makes one when none is set. The SSH server needs a non-empty token and never starts without one.
- Transport encryption with TLS is optional. The config key `swarm.tls.keyStore` names one PKCS12 key store that all peers share. A relative path starts at the working directory. The key `swarm.tls.passwordEnv` names the environment variable that holds the password of the store. `TlsSupport` builds the context from the store. If the key store is empty, the swarm does not use TLS. If `passwordEnv` names a variable that is not set, or the store cannot load, the program stops with an error.
- A peer message cannot approve a tool run. See [permissions-and-safety.md](permissions-and-safety.md).

## Limits

- Default maximum swarm size: 64 peers. The membership table ignores new peers above this size. The gossip fan-out stays at 1 peer in each round, so the load for each peer stays low.
- Default maximum message size: 1 MB.
- A peer drops a connection that fails the handshake in 5 s. A handshake frame has at most 4,096 bytes.
- A remote request (`history_query`, `agent_ask`, a roster, an attach) times out after 30 s.

## Configuration

The `swarm` section of the config has these keys: `enabled`, `bind`, `wka`, `token`, `gossipIntervalMs`, `discovery`, `rendezvous`, `ssh.enabled`, `ssh.bind`, `ssh.advertiseHost`, `tls.keyStore`, and `tls.passwordEnv`. See [configuration.md](configuration.md) for the keys. The flags `--swarm`, `--no-swarm`, and `--swarm-bind` set `enabled` and `bind`. The other limits in this document (ping timeout, suspect time, dead record time, swarm size, message size, handshake time, and request time) are fixed defaults of `SwarmConfig`. The config file does not set them.
