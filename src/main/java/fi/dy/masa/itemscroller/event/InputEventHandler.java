package fi.dy.masa.itemscroller.event;

import java.lang.invoke.MethodHandle;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.SlotItemHandler;
import fi.dy.masa.itemscroller.ItemScroller;
import fi.dy.masa.itemscroller.config.Configs;
import fi.dy.masa.itemscroller.proxy.ClientProxy;
import fi.dy.masa.itemscroller.recipes.RecipeStorage;
import fi.dy.masa.itemscroller.util.InventoryUtils;
import fi.dy.masa.itemscroller.util.MethodHandleUtils;

@SideOnly(Side.CLIENT)
public class InputEventHandler
{
    private static final InputEventHandler INSTANCE = new InputEventHandler();
    private boolean disabled;
    private int lastPosX;
    private int lastPosY;
    private int slotNumberLast;
    private final Set<Integer> draggedSlots = new HashSet<Integer>();
    private WeakReference<Slot> sourceSlotCandidate = new WeakReference<Slot>(null);
    private WeakReference<Slot> sourceSlot = new WeakReference<Slot>(null);
    private ItemStack stackInCursorLast = InventoryUtils.EMPTY_STACK;
    private RecipeStorage recipes;

    private static final MethodHandle methodHandle_getSlotAtPosition = MethodHandleUtils.getMethodHandleVirtual(GuiContainer.class,
            new String[] { "func_146975_c", "getSlotAtPosition" }, int.class, int.class);

    private InputEventHandler()
    {
        this.initializeRecipeStorage();
    }

    public static InputEventHandler getInstance()
    {
        return INSTANCE;
    }

    @SubscribeEvent
    public void onMouseInputEventPre(GuiScreenEvent.MouseInputEvent.Pre event)
    {
        GuiScreen guiScreen = event.getGui();
        Minecraft mc = guiScreen.mc;

        if (this.disabled == false && guiScreen instanceof GuiContainer &&
            mc != null && mc.player != null &&
            Configs.GUI_BLACKLIST.contains(guiScreen.getClass().getName()) == false)
        {
            GuiContainer gui = (GuiContainer) guiScreen;
            int dWheel = Mouse.getEventDWheel();
            boolean cancel = false;

            // Allow drag moving alone if the GUI is the creative inventory
            if (gui instanceof GuiContainerCreative)
            {
                if (dWheel == 0 &&
                    Configs.enableDragMovingShiftLeft ||
                    Configs.enableDragMovingShiftRight ||
                    Configs.enableDragMovingControlLeft)
                {
                    if (this.dragMoveItems(gui, mc))
                    {
                        event.setCanceled(true);
                    }
                }

                return;
            }

            if (dWheel != 0)
            {
                // When scrolling while the recipe view is open, change the selection instead of moving items
                if (this.isRecipeViewOpen())
                {
                    this.recipes.scrollSelection(dWheel < 0);
                }
                else
                {
                    cancel = InventoryUtils.tryMoveItems(gui, this.recipes, dWheel > 0);
                }
            }
            else
            {
                Slot slot = gui.getSlotUnderMouse();
                boolean isLeftClick = mouseEventIsLeftClick(mc);
                boolean isRightClick = mouseEventIsRightClick(mc);
                boolean isPickBlock = mouseEventIsPickBlock(mc);
                boolean isButtonDown = Mouse.getEventButtonState();

                if (isButtonDown && (isLeftClick || isRightClick || isPickBlock))
                {
                    final int mouseX = RenderEventHandler.getInstance().getMouseX();
                    final int mouseY = RenderEventHandler.getInstance().getMouseY();
                    int hoveredRecipeId = RenderEventHandler.getInstance().getHoveredRecipeId(mouseX, mouseY, this.recipes, gui, mc);

                    // Hovering over an item in the recipe view
                    if (hoveredRecipeId >= 0)
                    {
                        if (isLeftClick || isRightClick)
                        {
                            boolean changed = this.recipes.getSelection() != hoveredRecipeId;

                            // Left click on a recipe: Select the recipe and load items to the crafting grid
                            if (isLeftClick)
                            {
                                this.recipes.changeSelectedRecipe(hoveredRecipeId);

                                if (changed)
                                {
                                    InventoryUtils.clearFirstCraftingGridOfItems(this.recipes.getSelectedRecipe(), gui, false);
                                }
                            }
                            // Right click on a recipe: Only load items to the grid

                            InventoryUtils.tryMoveItemsToFirstCraftingGrid(this.recipes.getRecipe(hoveredRecipeId), gui, GuiScreen.isShiftKeyDown());
                        }
                        else if (isPickBlock)
                        {
                            InventoryUtils.clearFirstCraftingGridOfAllItems(gui);
                        }

                        event.setCanceled(true);

                        return;
                    }
                    // Pick-blocking over a crafting output slot with the recipe view open, store the recipe
                    else if (isPickBlock && this.isRecipeViewOpen() && InventoryUtils.isCraftingSlot(gui, slot))
                    {
                        this.recipes.storeCraftingRecipeToCurrentSelection(slot, gui, true);
                        cancel = true;

                        /*
                        if (Configs.craftingRecipesStoreToFile)
                        {
                            this.recipes.writeToDisk();
                        }
                        */
                    }
                }

                this.checkForItemPickup(mc);
                this.storeSourceSlotCandidate(slot, gui, mc);

                if (Configs.enableRightClickCraftingOneStack &&
                    isRightClick &&
                    isButtonDown &&
                    InventoryUtils.isCraftingSlot(gui, slot))
                {
                    InventoryUtils.rightClickCraftOneStack(gui);
                }
                else if (Configs.enableShiftPlaceItems && InventoryUtils.canShiftPlaceItems(gui))
                {
                    cancel |= this.shiftPlaceItems(slot, gui);
                }
                else if (Configs.enableShiftDropItems && this.canShiftDropItems(gui, mc))
                {
                    cancel |= this.shiftDropItems(gui);
                }
                else if (Configs.enableAltShiftClickEverything &&
                         isLeftClick &&
                         isButtonDown &&
                         GuiScreen.isAltKeyDown() &&
                         GuiScreen.isShiftKeyDown() &&
                         slot != null && InventoryUtils.isStackEmpty(slot.getStack()) == false)
                {
                    InventoryUtils.tryMoveStacks(slot, gui, false, true, false);
                    cancel = true;
                }
                else if (Configs.enableAltClickMatching &&
                         isLeftClick &&
                         Mouse.getEventButtonState() &&
                         GuiScreen.isAltKeyDown() &&
                         slot != null && InventoryUtils.isStackEmpty(slot.getStack()) == false)
                {
                    InventoryUtils.tryMoveStacks(slot, gui, true, true, false);
                    cancel = true;
                }
                else if (Configs.enableDragMovingShiftLeft ||
                         Configs.enableDragMovingShiftRight ||
                         Configs.enableDragMovingControlLeft)
                {
                    cancel |= this.dragMoveItems(gui, mc);
                }
            }

            if (cancel)
            {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onKeyInputEventPre(GuiScreenEvent.KeyboardInputEvent.Pre event)
    {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen guiScreen = mc.currentScreen;

        if (mc == null || mc.player == null || (guiScreen instanceof GuiContainer) == false)
        {
            return;
        }

        final int eventKey = Keyboard.getEventKey();
        GuiContainer gui = (GuiContainer) guiScreen;
        Slot slot = gui.getSlotUnderMouse();

        if (Keyboard.getEventKeyState() &&
            GuiScreen.isAltKeyDown() &&
            GuiScreen.isShiftKeyDown() &&
            GuiScreen.isCtrlKeyDown())
        {
            if (eventKey == Keyboard.KEY_C)
            {
                InventoryUtils.craftEverythingPossibleWithCurrentRecipe(this.recipes.getSelectedRecipe(), gui);
            }
            else if (eventKey == Keyboard.KEY_T)
            {
                InventoryUtils.throwAllCraftingResultsToGround(this.recipes.getSelectedRecipe(), gui);
            }
            else if (eventKey == Keyboard.KEY_M)
            {
                InventoryUtils.moveAllCraftingResultsToOtherInventory(this.recipes.getSelectedRecipe(), gui);
            }
            else if (eventKey == Keyboard.KEY_I)
            {
                if (slot != null)
                {
                    debugPrintSlotInfo(gui, slot);
                }
                else
                {
                    ItemScroller.logger.info("GUI class: {}", gui.getClass().getName());
                }
            }
        }

        // Drop all matching stacks from the same inventory when pressing Ctrl + Shift + Drop key
        if (Configs.enableControlShiftDropkeyDropItems && Keyboard.getEventKeyState() &&
            Configs.GUI_BLACKLIST.contains(gui.getClass().getName()) == false &&
            GuiScreen.isCtrlKeyDown() && GuiScreen.isShiftKeyDown() &&
            eventKey == mc.gameSettings.keyBindDrop.getKeyCode())
        {
            if (slot != null && slot.getHasStack())
            {
                InventoryUtils.dropStacks(gui, slot.getStack(), slot, true);
            }
        }
        // Toggle mouse functionality on/off
        else if (Keyboard.getEventKeyState() && ClientProxy.KEY_DISABLE.isActiveAndMatches(Keyboard.getEventKey()))
        {
            this.disabled = ! this.disabled;

            if (this.disabled)
            {
                mc.player.playSound(SoundEvents.BLOCK_NOTE_BASS, 0.8f, 0.8f);
            }
            else
            {
                mc.player.playSound(SoundEvents.BLOCK_NOTE_PLING, 0.5f, 1.0f);
            }
        }
        // Store or load a recipe
        else if (Keyboard.getEventKeyState() && this.isRecipeViewOpen() &&
                 eventKey >= Keyboard.KEY_1 && eventKey <= Keyboard.KEY_9)
        {
            int index = MathHelper.clamp(eventKey - Keyboard.KEY_1, 0, 8);
            this.recipes.changeSelectedRecipe(index);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event)
    {
        this.recipes.readFromDisk();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event)
    {
        if (Configs.craftingRecipesStoreToFile)
        {
            this.recipes.writeToDisk();
        }
    }

    public boolean isRecipeViewOpen()
    {
        int keyCode = ClientProxy.KEY_RECIPE.getKeyCode();
        return keyCode > 0 && keyCode < 256 && Keyboard.isKeyDown(keyCode) &&
                ClientProxy.KEY_RECIPE.getKeyModifier().isActive(ClientProxy.KEY_RECIPE.getKeyConflictContext());
    }

    public void initializeRecipeStorage()
    {
        this.recipes = new RecipeStorage(18, Configs.craftingScrollingSaveFileIsGlobal);
    }

    public RecipeStorage getRecipes()
    {
        return this.recipes;
    }

    /**
     * Store a reference to the slot when a slot is left or right clicked on.
     * The slot is then later used to determine which inventory an ItemStack was
     * picked up from, if the stack from the cursor is dropped while holding shift.
     */
    private void storeSourceSlotCandidate(Slot slot, GuiContainer gui, Minecraft mc)
    {
        // Left or right mouse button was pressed
        if (slot != null && Mouse.getEventButtonState() && (mouseEventIsLeftClick(mc) || mouseEventIsRightClick(mc)))
        {
            ItemStack stackCursor = mc.player.inventory.getItemStack();
            ItemStack stack = InventoryUtils.EMPTY_STACK;

            if (InventoryUtils.isStackEmpty(stackCursor) == false)
            {
                // Do a cheap copy without NBT data
                stack = new ItemStack(stackCursor.getItem(), InventoryUtils.getStackSize(stackCursor), stackCursor.getMetadata());
            }

            this.stackInCursorLast = stack;
            this.sourceSlotCandidate = new WeakReference<Slot>(slot);
        }
    }

    /**
     * Check if the (previous) mouse event resulted in picking up a new ItemStack to the cursor
     */
    private void checkForItemPickup(Minecraft mc)
    {
        ItemStack stackCursor = mc.player.inventory.getItemStack();

        // Picked up or swapped items to the cursor, grab a reference to the slot that the items came from
        // Note that we are only checking the item and metadata here!
        if (InventoryUtils.isStackEmpty(stackCursor) == false && stackCursor.isItemEqual(this.stackInCursorLast) == false)
        {
            this.sourceSlot = new WeakReference<Slot>(this.sourceSlotCandidate.get());
        }
    }

    private static void debugPrintSlotInfo(GuiContainer gui, Slot slot)
    {
        if (slot == null)
        {
            ItemScroller.logger.info("slot was null");
            return;
        }

        boolean hasSlot = gui.inventorySlots.inventorySlots.contains(slot);
        Object inv = slot instanceof SlotItemHandler ? ((SlotItemHandler) slot).getItemHandler() : slot.inventory;
        String stackStr = InventoryUtils.getStackString(slot.getStack());

        ItemScroller.logger.info(String.format("slot: slotNumber: %d, getSlotIndex(): %d, getHasStack(): %s, " +
                "slot class: %s, inv class: %s, Container's slot list has slot: %s, stack: %s",
                slot.slotNumber, slot.getSlotIndex(), slot.getHasStack(), slot.getClass().getName(),
                inv != null ? inv.getClass().getName() : "<null>", hasSlot ? " true" : "false", stackStr));
    }

    private boolean shiftPlaceItems(Slot slot, GuiContainer gui)
    {
        // Left click to place the items from the cursor to the slot
        InventoryUtils.leftClickSlot(gui, slot.slotNumber);

        // Ugly fix to prevent accidentally drag-moving the stack from the slot that it was just placed into...
        this.draggedSlots.add(slot.slotNumber);

        InventoryUtils.tryMoveStacks(slot, gui, true, false, false);

        return true;
    }

    private boolean shiftDropItems(GuiContainer gui)
    {
        ItemStack stackReference = gui.mc.player.inventory.getItemStack();

        if (InventoryUtils.isStackEmpty(stackReference) == false)
        {
            stackReference = stackReference.copy();

            // First drop the existing stack from the cursor
            InventoryUtils.dropItemsFromCursor(gui);

            InventoryUtils.dropStacks(gui, stackReference, this.sourceSlot.get(), true);
            return true;
        }

        return false;
    }

    private boolean canShiftDropItems(GuiContainer gui, Minecraft mc)
    {
        if (GuiScreen.isShiftKeyDown() && mouseEventIsLeftClick(mc) &&
            InventoryUtils.isStackEmpty(gui.mc.player.inventory.getItemStack()) == false)
        {
            int left = gui.getGuiLeft();
            int top = gui.getGuiTop();
            int xSize = gui.getXSize();
            int ySize = gui.getYSize();
            int mouseAbsX = Mouse.getEventX() * gui.width / gui.mc.displayWidth;
            int mouseAbsY = gui.height - Mouse.getEventY() * gui.height / gui.mc.displayHeight - 1;
            boolean isOutsideGui = mouseAbsX < left || mouseAbsY < top || mouseAbsX >= left + xSize || mouseAbsY >= top + ySize;

            return isOutsideGui && this.getSlotAtPosition(gui, mouseAbsX - left, mouseAbsY - top) == null;
        }

        return false;
    }

    private boolean dragMoveItems(GuiContainer gui, Minecraft mc)
    {
        int mouseX = Mouse.getEventX() * gui.width / mc.displayWidth;
        int mouseY = gui.height - Mouse.getEventY() * gui.height / mc.displayHeight - 1;

        if (InventoryUtils.isStackEmpty(mc.player.inventory.getItemStack()) == false)
        {
            // Updating these here is part of the fix to preventing a drag after shift + place
            this.lastPosX = mouseX;
            this.lastPosY = mouseY;
            return false;
        }

        boolean eventKeyIsLeftButton = mouseEventIsLeftClick(mc);
        boolean eventKeyIsRightButton = mouseEventIsRightClick(mc);
        boolean leftButtonDown = Mouse.isButtonDown(mc.gameSettings.keyBindAttack.getKeyCode() + 100);
        boolean rightButtonDown = Mouse.isButtonDown(mc.gameSettings.keyBindUseItem.getKeyCode() + 100);
        boolean isShiftDown = GuiScreen.isShiftKeyDown();
        boolean isControlDown = GuiScreen.isCtrlKeyDown();
        boolean eitherMouseButtonDown = leftButtonDown || rightButtonDown;

        if ((isShiftDown && leftButtonDown && Configs.enableDragMovingShiftLeft == false) ||
            (isShiftDown && rightButtonDown && Configs.enableDragMovingShiftRight == false) ||
            (isControlDown && eitherMouseButtonDown && Configs.enableDragMovingControlLeft == false))
        {
            return false;
        }

        boolean leaveOneItem = leftButtonDown == false;
        boolean moveOnlyOne = isShiftDown == false;
        boolean cancel = false;

        if (Mouse.getEventButtonState())
        {
            if (((eventKeyIsLeftButton || eventKeyIsRightButton) && isControlDown && Configs.enableDragMovingControlLeft) ||
                (eventKeyIsRightButton && isShiftDown && Configs.enableDragMovingShiftRight))
            {
                // Reset this or the method call won't do anything...
                this.slotNumberLast = -1;
                cancel = this.dragMoveFromSlotAtPosition(gui, mouseX, mouseY, leaveOneItem, moveOnlyOne);
            }
        }

        // Check that either mouse button is down
        if (cancel == false && (isShiftDown || isControlDown) && eitherMouseButtonDown)
        {
            int distX = mouseX - this.lastPosX;
            int distY = mouseY - this.lastPosY;
            int absX = Math.abs(distX);
            int absY = Math.abs(distY);

            if (absX > absY)
            {
                int inc = distX > 0 ? 1 : -1;

                for (int x = this.lastPosX; ; x += inc)
                {
                    int y = absX != 0 ? this.lastPosY + ((x - this.lastPosX) * distY / absX) : mouseY;
                    this.dragMoveFromSlotAtPosition(gui, x, y, leaveOneItem, moveOnlyOne);

                    if (x == mouseX)
                    {
                        break;
                    }
                }
            }
            else
            {
                int inc = distY > 0 ? 1 : -1;

                for (int y = this.lastPosY; ; y += inc)
                {
                    int x = absY != 0 ? this.lastPosX + ((y - this.lastPosY) * distX / absY) : mouseX;
                    this.dragMoveFromSlotAtPosition(gui, x, y, leaveOneItem, moveOnlyOne);

                    if (y == mouseY)
                    {
                        break;
                    }
                }
            }
        }

        this.lastPosX = mouseX;
        this.lastPosY = mouseY;

        // Always update the slot under the mouse.
        // This should prevent a "double click/move" when shift + left clicking on slots that have more
        // than one stack of items. (the regular slotClick() + a "drag move" from the slot that is under the mouse
        // when the left mouse button is pressed down and this code runs).
        Slot slot = this.getSlotAtPosition(gui, mouseX, mouseY);

        if (slot != null)
        {
            if (gui instanceof GuiContainerCreative)
            {
                boolean isPlayerInv = ((GuiContainerCreative) gui).getSelectedTabIndex() == CreativeTabs.INVENTORY.getTabIndex();
                int slotNumber = isPlayerInv ? slot.getSlotIndex() : slot.slotNumber;
                this.slotNumberLast = slotNumber;
            }
            else
            {
                this.slotNumberLast = slot.slotNumber;
            }
        }
        else
        {
            this.slotNumberLast = -1;
        }

        if (eitherMouseButtonDown == false)
        {
            this.draggedSlots.clear();
        }

        return cancel;
    }

    private boolean dragMoveFromSlotAtPosition(GuiContainer gui, int x, int y, boolean leaveOneItem, boolean moveOnlyOne)
    {
        if (gui instanceof GuiContainerCreative)
        {
            return this.dragMoveFromSlotAtPositionCreative(gui, x, y, leaveOneItem, moveOnlyOne);
        }

        Slot slot = this.getSlotAtPosition(gui, x, y);
        Minecraft mc = Minecraft.getMinecraft();
        boolean flag = slot != null && InventoryUtils.isValidSlot(slot, gui, true) && slot.canTakeStack(mc.player);
        boolean cancel = flag && (leaveOneItem || moveOnlyOne);

        if (flag && slot.slotNumber != this.slotNumberLast &&
            (moveOnlyOne == false || this.draggedSlots.contains(slot.slotNumber) == false))
        {
            if (moveOnlyOne)
            {
                InventoryUtils.tryMoveSingleItemToOtherInventory(slot, gui);
            }
            else if (leaveOneItem)
            {
                InventoryUtils.tryMoveAllButOneItemToOtherInventory(slot, gui);
            }
            else
            {
                InventoryUtils.shiftClickSlot(gui, slot.slotNumber);
                cancel = true;
            }

            this.draggedSlots.add(slot.slotNumber);
        }

        return cancel;
    }

    private boolean dragMoveFromSlotAtPositionCreative(GuiContainer gui, int x, int y, boolean leaveOneItem, boolean moveOnlyOne)
    {
        GuiContainerCreative guiCreative = (GuiContainerCreative) gui;
        Slot slot = this.getSlotAtPosition(gui, x, y);
        boolean isPlayerInv = guiCreative.getSelectedTabIndex() == CreativeTabs.INVENTORY.getTabIndex();

        // Only allow dragging from the hotbar slots
        if (slot == null || (slot.getClass() != Slot.class && isPlayerInv == false))
        {
            return false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        boolean flag = slot != null && InventoryUtils.isValidSlot(slot, gui, true) && slot.canTakeStack(mc.player);
        boolean cancel = flag && (leaveOneItem || moveOnlyOne);
        // The player inventory tab of the creative inventory uses stupid wrapped
        // slots that all have slotNumber = 0 on the outer instance ;_;
        // However in that case we can use the slotIndex which is easy enough to get.
        int slotNumber = isPlayerInv ? slot.getSlotIndex() : slot.slotNumber;

        if (flag && slotNumber != this.slotNumberLast && this.draggedSlots.contains(slotNumber) == false)
        {
            if (moveOnlyOne)
            {
                this.leftClickSlot(guiCreative, slot, slotNumber);
                this.rightClickSlot(guiCreative, slot, slotNumber);
                this.shiftClickSlot(guiCreative, slot, slotNumber);
                this.leftClickSlot(guiCreative, slot, slotNumber);

                cancel = true;
            }
            else if (leaveOneItem)
            {
                // Too lazy to try to duplicate the proper code for the weird creative inventory...
                if (isPlayerInv == false)
                {
                    this.leftClickSlot(guiCreative, slot, slotNumber);
                    this.rightClickSlot(guiCreative, slot, slotNumber);

                    // Delete the rest of the stack by placing it in the first creative "source slot"
                    Slot slotFirst = gui.inventorySlots.inventorySlots.get(0);
                    this.leftClickSlot(guiCreative, slotFirst, slotFirst.slotNumber);
                }

                cancel = true;
            }
            else
            {
                this.shiftClickSlot(gui, slot, slotNumber);
                cancel = true;
            }

            this.draggedSlots.add(slotNumber);
        }

        return cancel;
    }

    public static boolean mouseEventIsLeftClick(Minecraft mc)
    {
        return Mouse.getEventButton() == mc.gameSettings.keyBindAttack.getKeyCode() + 100;
    }

    public static boolean mouseEventIsRightClick(Minecraft mc)
    {
        return Mouse.getEventButton() == mc.gameSettings.keyBindUseItem.getKeyCode() + 100;
    }

    public static boolean mouseEventIsPickBlock(Minecraft mc)
    {
        return Mouse.getEventButton() == mc.gameSettings.keyBindPickBlock.getKeyCode() + 100;
    }

    private void leftClickSlot(GuiContainer gui, Slot slot, int slotNumber)
    {
        InventoryUtils.clickSlot(gui, slot, slotNumber, 0, ClickType.PICKUP);
    }

    private void rightClickSlot(GuiContainer gui, Slot slot, int slotNumber)
    {
        InventoryUtils.clickSlot(gui, slot, slotNumber, 1, ClickType.PICKUP);
    }

    private void shiftClickSlot(GuiContainer gui, Slot slot, int slotNumber)
    {
        InventoryUtils.clickSlot(gui, slot, slotNumber, 0, ClickType.QUICK_MOVE);
    }

    private Slot getSlotAtPosition(GuiContainer gui, int x, int y)
    {
        try
        {
            return (Slot) methodHandle_getSlotAtPosition.invokeExact(gui, x, y);
        }
        catch (Throwable e)
        {
            ItemScroller.logger.error("Error while trying invoke GuiContainer#getSlotAtPosition() from {}", gui.getClass().getSimpleName(), e);
        }

        return null;
    }
}
