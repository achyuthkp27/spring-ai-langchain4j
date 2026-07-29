import { describe, it, expect } from "vitest";
import { mergeById } from "./useChatStream";

interface Item {
  id: string;
  value: string;
}

describe("mergeById", () => {
  it("appends new items that aren't already present", () => {
    const existing: Item[] = [{ id: "a", value: "1" }];
    const incoming: Item[] = [{ id: "b", value: "2" }];
    const merged = mergeById(existing, incoming, (i) => i.id);
    expect(merged).toEqual([
      { id: "a", value: "1" },
      { id: "b", value: "2" },
    ]);
  });

  it("replaces an existing item with the same id instead of duplicating it", () => {
    const existing: Item[] = [{ id: "a", value: "1" }];
    const incoming: Item[] = [{ id: "a", value: "updated" }];
    const merged = mergeById(existing, incoming, (i) => i.id);
    expect(merged).toEqual([{ id: "a", value: "updated" }]);
  });

  it("treats an undefined existing list as empty", () => {
    const incoming: Item[] = [{ id: "a", value: "1" }];
    const merged = mergeById(undefined, incoming, (i) => i.id);
    expect(merged).toEqual([{ id: "a", value: "1" }]);
  });
});
