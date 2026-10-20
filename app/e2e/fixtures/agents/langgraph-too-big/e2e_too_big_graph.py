"""A chain of 199 nodes; with __start__ and __end__ the topology has 201, one past Homer's limit."""

from typing import TypedDict

from langgraph.graph import END, START, StateGraph


class State(TypedDict, total=False):
    step: int


def step(state: State) -> State:
    return {"step": state.get("step", 0) + 1}


graph = StateGraph(State)
names = [f"n{i}" for i in range(199)]
for name in names:
    graph.add_node(name, step)
for source, target in zip([START, *names], [*names, END]):
    graph.add_edge(source, target)
