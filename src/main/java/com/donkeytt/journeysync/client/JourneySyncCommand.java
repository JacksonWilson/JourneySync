package com.donkeytt.journeysync.client;

import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentTranslation;

/**
 * Client-side {@code /journeysync} (alias {@code /jsync}) command: status, pause, resume, resync.
 */
public class JourneySyncCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "journeysync";
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List getCommandAliases() {
        return Arrays.asList("jsync");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/journeysync [status|pause|resume|resync]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        ClientSync manager = ClientSync.instance();
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "status":
                sender.addChatMessage(new ChatComponentTranslation("journeysync.chat.status", manager.statusLine()));
                break;
            case "pause":
                manager.setPaused(true);
                break;
            case "resume":
                manager.setPaused(false);
                break;
            case "resync":
                sender.addChatMessage(
                    new ChatComponentTranslation(
                        manager.resync() ? "journeysync.chat.resync_started" : "journeysync.chat.resync_unavailable"));
                break;
            default:
                sender.addChatMessage(new ChatComponentTranslation("journeysync.chat.usage", getCommandUsage(sender)));
                break;
        }
    }
}
