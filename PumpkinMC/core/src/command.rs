//! `PartyCommand` (`/party`, `/p`): invite, accept, deny, join, leave, list, kick, promote, demote, disband, warp,
//! settings, chat; `/p <player>` is a shortcut for `/p invite <player>`. `PartyChatCommand` (`/pchat`, `/pc`,
//! `/party-chat`): `/pc <message>` sends once, `/pc toggle` (or `/pc` alone) toggles the chat lock, `/pc spy` lets
//! staff read every party's chat (without the permission it is an ordinary message).

use std::time::Duration;

use uuid::Uuid;

use crate::manager::Outcome;
use crate::party::{PartyMember, PartyRole, Toggle};
use crate::text::Text;
use crate::{PartiesCore, Sender, durations};

/// The subcommands, in the order Java suggests them.
pub const PARTY_SUBCOMMANDS: [&str; 14] = [
    "invite", "accept", "deny", "join", "list", "leave", "warp", "kick", "promote", "demote", "disband", "settings",
    "chat", "help",
];
const SETTINGS_USAGE: &str =
    "/party settings [autowarp|chat|allinvite|allwarp|public|mute|maxsize <size|max>|slowmode <time|off>]";

/// Splits a command line into Bukkit/Velocity-style arguments (runs of spaces count once).
pub fn split_args(line: &str) -> Vec<String> {
    line.split_whitespace().map(str::to_owned).collect()
}

/// Lines joined with newlines, as Adventure's `JoinConfiguration.newlines()`.
fn lines(lines: Vec<Text>) -> Text {
    let mut out = Text::default();
    for (i, line) in lines.into_iter().enumerate() {
        if i > 0 {
            out.append(Text::plain("\n"));
        }
        out.append(line);
    }
    out
}

impl PartiesCore {
    /// `/party <args>`.
    pub fn party_command(&self, sender: &Sender, args: &[String]) {
        let outcome = self.handle_party(sender, args);
        if !outcome.message_key().is_empty() {
            self.reply(sender.id, self.messages.simple(outcome.message_key()));
        }
        self.dispatch();
    }

    /// `/pchat <args>`.
    pub fn chat_command(&self, sender: &Sender, args: &[String]) {
        let word = if args.len() == 1 { args[0].to_lowercase() } else { String::new() };
        if args.is_empty() || word == "toggle" {
            self.toggle_lock(sender.id);
        } else if word == "spy" && sender.can_spy {
            self.toggle_spy(sender.id);
        } else {
            self.send_chat(sender.id, &args.join(" "), 0);
        }
        self.dispatch();
    }

    /// The manager's verdict; [`Outcome::Success`] when the reply was already sent.
    fn handle_party(&self, sender: &Sender, args: &[String]) -> Outcome {
        let me = sender.id;
        let sub = args.first().map_or_else(|| "help".to_owned(), |a| a.to_lowercase());
        match sub.as_str() {
            "help" => self.answer(me, self.messages.simple("help")),
            "list" => self.list(me),
            "leave" => self.manager.leave(me),
            "disband" => self.manager.disband(me),
            "warp" => match &sender.server {
                Some(server) => self.manager.warp(me, server),
                None => Outcome::Success, // between servers
            },
            "settings" => self.settings(me, args),
            "chat" => {
                if args.len() == 1 {
                    self.toggle_lock(me);
                } else {
                    self.send_chat(me, &args[1..].join(" "), 0);
                }
                Outcome::Success
            }
            "invite" | "accept" | "deny" | "join" | "kick" | "promote" | "demote" => match args.get(1) {
                None => self.usage(me, &format!("/party {sub} <player>")),
                Some(name) => self.targeted(me, &sub, name),
            },
            _ if args.len() == 1 => self.targeted(me, "invite", &args[0]),
            _ => self.answer(me, self.messages.simple("help")),
        }
    }

    fn targeted(&self, me: Uuid, action: &str, name: &str) -> Outcome {
        let network = &self.network;
        match action {
            "invite" => match network.find_online(name) {
                Some(target) => self.manager.invite(me, target),
                None => self.answer(
                    me,
                    self.messages.render("error.player-not-found", &[("player", name.to_owned())], &[], &[]),
                ),
            },
            "accept" => network.uuid_of(name).map_or(Outcome::NoInvite, |inviter| self.manager.accept(me, inviter)),
            "deny" => network.uuid_of(name).map_or(Outcome::NoInvite, |inviter| self.manager.deny(me, inviter)),
            "join" => network.uuid_of(name).map_or(Outcome::NotPublic, |member| self.manager.join(me, member)),
            "kick" => self.member_named(me, name).map_or_else(|| self.not_member(me), |t| self.manager.kick(me, t)),
            "promote" => {
                self.member_named(me, name).map_or_else(|| self.not_member(me), |t| self.manager.promote(me, t))
            }
            _ => self.member_named(me, name).map_or_else(|| self.not_member(me), |t| self.manager.demote(me, t)),
        }
    }

    /// `/p settings` shows the menu; `/p settings <key> [value]` changes one setting.
    fn settings(&self, me: Uuid, args: &[String]) -> Outcome {
        if args.len() == 1 {
            return self.menu(me);
        }
        let key = args[1].to_lowercase();
        if let Some(toggle) = Toggle::by_key(&key) {
            return self.manager.toggle(me, toggle);
        }
        if args.len() < 3 || key != "maxsize" && key != "slowmode" {
            return self.usage(me, SETTINGS_USAGE);
        }
        let value = args[2].to_lowercase();
        if key == "maxsize" {
            if value == "max" {
                return self.manager.set_max_size(me, 0);
            }
            return match value.parse::<i32>() {
                Ok(size) => self.manager.set_max_size(me, i64::from(size)),
                Err(_) => self.usage(me, SETTINGS_USAGE),
            };
        }
        let seconds = if value == "off" { Ok(0) } else { durations::parse(&value).map(|d| d.as_secs()) };
        match seconds {
            Ok(seconds) => self.manager.set_slow_mode(me, i64::try_from(seconds.min(i32::MAX as u64)).unwrap_or(0)),
            Err(_) => self.usage(me, SETTINGS_USAGE),
        }
    }

    fn menu(&self, me: Uuid) -> Outcome {
        let Some(party) = self.manager.party_of(me) else { return Outcome::NotInParty };
        let settings = party.settings;
        let mut out = vec![self.messages.simple("settings.header")];
        for toggle in Toggle::ALL {
            let state =
                self.messages.simple(if settings.get(toggle) { "settings.state-on" } else { "settings.state-off" });
            let name = self.messages.simple(&format!("settings.name.{}", toggle.key()));
            // Setting keys are plain words: passed like names so they also work inside click arguments.
            out.push(self.messages.render(
                "settings.toggle",
                &[("key", toggle.key().to_owned())],
                &[],
                &[("name", name), ("state", state)],
            ));
        }
        let limit = self.manager.size_limit(&party);
        let values = [("max", settings.capped_at(limit).to_string()), ("limit", limit.to_string())];
        out.push(self.messages.render_values("settings.max-size-line", &values));
        let slow = if settings.slow_mode_seconds == 0 {
            self.messages.simple("settings.slow-mode-none")
        } else {
            Text::plain(durations::format(Duration::from_secs(u64::from(settings.slow_mode_seconds))))
        };
        out.push(self.messages.render("settings.slow-mode-line", &[], &[], &[("time", slow)]));
        out.push(self.messages.simple("settings.footer"));
        self.answer(me, lines(out))
    }

    fn list(&self, me: Uuid) -> Outcome {
        let Some(party) = self.manager.party_of(me) else { return Outcome::NotInParty };
        let header = [("count", party.size().to_string()), ("max", self.manager.max_size(&party).to_string())];
        let mut out = vec![self.messages.render_values("list.header", &header)];
        for role in PartyRole::ALL {
            let key = match role {
                PartyRole::Owner => "list.leader",
                PartyRole::Moderator => "list.moderator",
                PartyRole::Member => "list.member",
            };
            out.extend(party.members.iter().filter(|m| m.role == role).map(|m| self.row(key, m)));
        }
        out.push(self.messages.simple("list.footer"));
        self.answer(me, lines(out))
    }

    fn row(&self, key: &str, member: &PartyMember) -> Text {
        let status = self.messages.simple(if member.online() { "status.online" } else { "status.offline" });
        let name = self.network.name_of(member.id).unwrap_or_else(|| "?".to_owned());
        self.messages.render(key, &[("player", name)], &[], &[("status", status)])
    }

    /// Resolves `name` among the actor's party members (works for offline members too).
    fn member_named(&self, me: Uuid, name: &str) -> Option<Uuid> {
        let party = self.manager.party_of(me)?;
        party
            .member_ids()
            .into_iter()
            .find(|id| self.network.name_of(*id).is_some_and(|n| n.eq_ignore_ascii_case(name)))
    }

    fn not_member(&self, me: Uuid) -> Outcome {
        if self.manager.party_of(me).is_some() { Outcome::TargetNotInParty } else { Outcome::NotInParty }
    }

    fn usage(&self, me: Uuid, usage: &str) -> Outcome {
        self.answer(me, self.messages.render_values("error.usage", &[("usage", usage.to_owned())]))
    }

    fn answer(&self, me: Uuid, text: Text) -> Outcome {
        self.reply(me, text);
        Outcome::Success
    }
}
