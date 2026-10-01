/*
 * This file is part of sdlink-core, licensed under the MIT License (MIT).
 * Copyright HypherionSA and Contributors
 */
package com.hypherionmc.sdlink.core.discord.commands.slash.verification;

import com.hypherionmc.sdlink.api.accounts.MinecraftAccount;
import com.hypherionmc.sdlink.api.messaging.Result;
import com.hypherionmc.sdlink.core.config.SDLinkConfig;
import com.hypherionmc.sdlink.core.database.SDLinkAccount;
import com.hypherionmc.sdlink.core.discord.VerificationRateLimiter;
import com.hypherionmc.sdlink.core.discord.commands.slash.SDLinkSlashCommand;
import com.hypherionmc.sdlink.core.managers.DatabaseManager;
import com.hypherionmc.sdlink.util.translations.SDText;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.Collections;

public final class VerifyAccountCommand extends SDLinkSlashCommand {

    public VerifyAccountCommand() {
        super(false);
        this.name = "verify";
        this.help = SDText.translate("command.verify.help").toString();
        this.guildOnly = false;

        this.options = Collections.singletonList(new OptionData(OptionType.INTEGER, "code", "The verification code from the Minecraft Kick Message").setRequired(true));
    }

    @Override
    protected void execute(SlashCommandEvent event) {
        if (!SDLinkConfig.INSTANCE.accessControl.allowVerifyInDm && !event.isFromGuild()) {
            return;
        }

        event.deferReply(SDLinkConfig.INSTANCE.botConfig.silentReplies).queue();

        int mcCode = event.getOption("code") != null ? event.getOption("code").getAsInt() : 0;

        if (mcCode == 0) {
            event.getHook().sendMessage(SDText.translate("command.verify.missing_code").toString()).setEphemeral(SDLinkConfig.INSTANCE.botConfig.silentReplies).queue();
            return;
        }

        Guild guild = event.isFromGuild() ? event.getGuild() : (event.getJDA().getGuilds().isEmpty() ? null : event.getJDA().getGuilds().get(0));
        if (guild == null) {
            event.getHook().sendMessage(SDText.translate("error.no_discord_server").toString()).setEphemeral(SDLinkConfig.INSTANCE.botConfig.silentReplies).queue();
            return;
        }

        Member m = event.isFromGuild() ? event.getMember() : guild.getMemberById(event.getUser().getId());
        if (m == null) {
            event.getHook().sendMessage(SDText.translate("error.not_a_member_of", guild.getName()).toString()).setEphemeral(SDLinkConfig.INSTANCE.botConfig.silentReplies).queue();
            return;
        }

        if (!VerificationRateLimiter.INSTANCE.allowAttempt(m.getId())) {
            event.getHook().sendMessage(SDText.translate("command.verify.rate_limited").toString()).setEphemeral(true).queue();
            return;
        }

        String code = String.valueOf(mcCode);
        SDLinkAccount account = DatabaseManager.INSTANCE.findAccountByVerificationCode(code);
        if (account == null) {
            event.getHook().sendMessage(SDText.translate("command.verify.failed").toString()).setEphemeral(SDLinkConfig.INSTANCE.botConfig.silentReplies).queue();
            return;
        }

        Result result = MinecraftAccount.of(account).verifyAccount(m, guild, code);
        if (!result.isError()) VerificationRateLimiter.INSTANCE.clear(m.getId());
        event.getHook().sendMessage(result.getMessage()).setEphemeral(true).queue();
    }

}
