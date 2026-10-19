package com.imageframe;

import com.imageframe.commands.ImageFrameCommand;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandTreeTest {

    @BeforeAll
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void createTreeHasNestedArguments() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ImageFrameCommand.register(dispatcher);
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("imageframe");
        assertNotNull(root, "imageframe root");
        CommandNode<CommandSourceStack> create = root.getChild("create");
        assertNotNull(create, "create node");
        CommandNode<CommandSourceStack> name = create.getChild("name");
        assertNotNull(name, "name node");
        CommandNode<CommandSourceStack> url = name.getChild("url");
        assertNotNull(url, "url node");
        List<String> children = url.getChildren().stream().map(CommandNode::getName).sorted().toList();
        System.out.println("url children: " + children);
        assertTrue(children.contains("selection"), "selection child");
        assertTrue(children.contains("width"), "width child");
        CommandNode<CommandSourceStack> width = url.getChild("width");
        CommandNode<CommandSourceStack> height = width.getChild("height");
        assertNotNull(height, "height node");
        System.out.println("height executes: " + (height.getCommand() != null));
        assertNotNull(height.getCommand(), "height executable");
    }
}
