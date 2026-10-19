package com.awesomehippo.bookpreview;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = BookPreview.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class BookPreviewClient {
    private static final int BOOK = 192;
    private static final int TEXT_W = 114;
    private static final int TEXT_H = 128;
    private static final int TEXT_X = 36;
    private static final int TEXT_Y = 30;
    private static final int ARROW_W = 23;
    private static final int ARROW_H = 13;
    private static final int BACK_X = 43;
    private static final int NEXT_X = 116;
    private static final int ARROW_Y = 157;
    private static final int GAP = 4;

    private static AbstractContainerScreen<?> screen;
    private static Slot slot;
    private static ItemStack stack = ItemStack.EMPTY;
    private static BookViewScreen.BookAccess access = BookViewScreen.EMPTY_ACCESS;
    private static int page;
    private static int cachedPage = -1;
    private static List<FormattedCharSequence> lines = List.of();
    private static Component pageMsg = CommonComponents.EMPTY;
    private static int bookX;
    private static int bookY;
    private static boolean readDown;

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        AbstractContainerScreen<?> container = containerOf(event.getScreen());
        if (container == null || !alive(container)) {
            return;
        }
        place(container);
        if (movedOff(container, event.getMouseX(), event.getMouseY())) {
            return;
        }
        draw(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }

    @SubscribeEvent
    public static void onKey(ScreenEvent.KeyPressed.Pre event) {
        AbstractContainerScreen<?> container = containerOf(event.getScreen());
        if (container == null || event.getScreen().getFocused() instanceof EditBox) {
            return;
        }
        if (Keybinds.READ.isActiveAndMatches(InputConstants.getKey(event.getKeyCode(), event.getScanCode()))) {
            if (readDown) {
                if (screen != null) {
                    event.setCanceled(true);
                }
                return;
            }
            readDown = true;
            alive(container);
            read(event, container);
            return;
        }
        int key = event.getKeyCode();
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_PAGE_UP) {
            if (alive(container) && !otherSlot(container)) {
                turn(-1);
                event.setCanceled(true);
            }
            return;
        }
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            if (alive(container) && !otherSlot(container)) {
                turn(1);
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onKeyUp(ScreenEvent.KeyReleased.Pre event) {
        if (Keybinds.READ.isActiveAndMatches(InputConstants.getKey(event.getKeyCode(), event.getScanCode()))) {
            readDown = false;
        }
    }

    @SubscribeEvent
    public static void onMouse(ScreenEvent.MouseButtonPressed.Pre event) {
        AbstractContainerScreen<?> container = containerOf(event.getScreen());
        if (container == null || !overBook(container, event.getMouseX(), event.getMouseY())) {
            return;
        }
        if (event.getButton() == 0) {
            if (page > 0 && overArrow(event.getMouseX(), event.getMouseY(), BACK_X)) {
                turn(-1);
            } else if (page + 1 < access.getPageCount() && overArrow(event.getMouseX(), event.getMouseY(), NEXT_X)) {
                turn(1);
            }
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseUp(ScreenEvent.MouseButtonReleased.Pre event) {
        AbstractContainerScreen<?> container = containerOf(event.getScreen());
        if (container != null && overBook(container, event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        AbstractContainerScreen<?> container = containerOf(event.getScreen());
        if (container == null || !alive(container)) {
            return;
        }
        place(container);
        if (movedOff(container, event.getMouseX(), event.getMouseY())) {
            return;
        }
        if (!inBook(event.getMouseX(), event.getMouseY()) && container.getSlotUnderMouse() != slot) {
            return;
        }
        if (event.getScrollDelta() > 0) {
            turn(-1);
        } else if (event.getScrollDelta() < 0) {
            turn(1);
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?>) {
            readDown = false;
        }
        if (event.getScreen() == screen) {
            close();
        }
    }

    private static AbstractContainerScreen<?> containerOf(Screen current) {
        if (current instanceof AbstractContainerScreen<?> container) {
            return container;
        }
        return null;
    }

    private static void read(ScreenEvent.KeyPressed.Pre event, AbstractContainerScreen<?> container) {
        Slot hovered = container.getSlotUnderMouse();
        if (screen == container && (hovered == null || hovered == slot)) {
            close();
            event.setCanceled(true);
            return;
        }
        if (hovered != null && open(container, hovered)) {
            event.setCanceled(true);
            return;
        }
        if (screen == container) {
            close();
            event.setCanceled(true);
        }
    }

    private static boolean open(AbstractContainerScreen<?> container, Slot hovered) {
        ItemStack next = hovered.getItem().copy();
        BookViewScreen.BookAccess book = BookViewScreen.BookAccess.fromItem(next);
        if (book.getPageCount() <= 0) {
            return false;
        }
        screen = container;
        slot = hovered;
        stack = next;
        access = book;
        page = 0;
        cachedPage = -1;
        return true;
    }

    private static boolean movedOff(AbstractContainerScreen<?> container, double mx, double my) {
        if (inBook(mx, my) || !otherSlot(container)) {
            return false;
        }
        close();
        return true;
    }

    private static boolean otherSlot(AbstractContainerScreen<?> container) {
        Slot hovered = container.getSlotUnderMouse();
        return hovered != null && hovered != slot;
    }

    private static boolean alive(AbstractContainerScreen<?> container) {
        if (screen == null) {
            return false;
        }
        if (screen != container || slot == null || !ItemStack.matches(slot.getItem(), stack)) {
            close();
            return false;
        }
        return true;
    }

    private static void close() {
        screen = null;
        slot = null;
        stack = ItemStack.EMPTY;
        access = BookViewScreen.EMPTY_ACCESS;
        page = 0;
        cachedPage = -1;
        lines = List.of();
        pageMsg = CommonComponents.EMPTY;
    }

    private static void turn(int dir) {
        int next = page + dir;
        if (next < 0 || next >= access.getPageCount()) {
            return;
        }
        page = next;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0F));
    }

    private static void place(AbstractContainerScreen<?> container) {
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        bookX = container.getGuiLeft() + container.getXSize() + GAP;
        bookY = container.getGuiTop();
        if (bookX + BOOK > width) {
            bookX = container.getGuiLeft() - BOOK - GAP;
        }
        if (bookX < 0) {
            bookX = 0;
        }
        if (bookY + BOOK > height) {
            bookY = height - BOOK;
        }
        if (bookY < 0) {
            bookY = 0;
        }
    }

    private static boolean overBook(AbstractContainerScreen<?> container, double mx, double my) {
        if (!alive(container)) {
            return false;
        }
        place(container);
        return inBook(mx, my);
    }

    private static boolean inBook(double mx, double my) {
        return mx >= bookX && my >= bookY && mx < bookX + BOOK && my < bookY + BOOK;
    }

    private static boolean overArrow(double mx, double my, int arrowX) {
        return mx >= bookX + arrowX && my >= bookY + ARROW_Y && mx < bookX + arrowX + ARROW_W && my < bookY + ARROW_Y + ARROW_H;
    }

    private static void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        if (cachedPage != page) {
            lines = font.split(access.getPage(page), TEXT_W);
            pageMsg = Component.translatable("book.pageIndicator", page + 1, Math.max(access.getPageCount(), 1));
            cachedPage = page;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        graphics.blit(BookViewScreen.BOOK_LOCATION, bookX, bookY, 0, 0, BOOK, BOOK);
        if (page > 0) {
            blitArrow(graphics, BACK_X, mouseX, mouseY, false);
        }
        if (page + 1 < access.getPageCount()) {
            blitArrow(graphics, NEXT_X, mouseX, mouseY, true);
        }
        graphics.drawString(font, pageMsg, bookX + BOOK - 44 - font.width(pageMsg), bookY + 16, 0, false);
        int shown = Math.min(TEXT_H / 9, lines.size());
        for (int i = 0; i < shown; i++) {
            graphics.drawString(font, lines.get(i), bookX + TEXT_X, bookY + TEXT_Y + i * 9, 0, false);
        }
        RenderSystem.enableDepthTest();
    }

    private static void blitArrow(GuiGraphics graphics, int arrowX, int mouseX, int mouseY, boolean forward) {
        int u = overArrow(mouseX, mouseY, arrowX) ? ARROW_W : 0;
        graphics.blit(BookViewScreen.BOOK_LOCATION, bookX + arrowX, bookY + ARROW_Y, u, forward ? 192 : 205, ARROW_W, ARROW_H);
    }
}
