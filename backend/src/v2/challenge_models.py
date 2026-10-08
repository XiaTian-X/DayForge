"""Additive, owner/activity-constrained challenge history and birth bindings."""

from sqlalchemy import CheckConstraint, ForeignKeyConstraint, UniqueConstraint
from sqlmodel import Field, SQLModel


class ActivityChallengeRound(SQLModel, table=True):
    __tablename__ = "activity_challenge_rounds"
    __table_args__ = (
        UniqueConstraint(
            "owner_user_id", "public_id", name="uq_challenge_round_public"
        ),
        UniqueConstraint(
            "activity_node_id", "generation", name="uq_challenge_round_generation"
        ),
        UniqueConstraint(
            "source_device_id",
            "restart_operation_uuid",
            name="uq_challenge_round_source",
        ),
        UniqueConstraint(
            "owner_user_id",
            "activity_node_id",
            "id",
            name="uq_challenge_round_owned_identity",
        ),
        ForeignKeyConstraint(
            ["owner_user_id", "activity_node_id"],
            ["plan_nodes.owner_user_id", "plan_nodes.id"],
            name="fk_challenge_round_activity",
        ),
        ForeignKeyConstraint(
            ["owner_user_id", "source_device_id"],
            ["client_devices.user_id", "client_devices.id"],
            name="fk_challenge_round_device",
        ),
        CheckConstraint(
            "generation BETWEEN 0 AND 2147483647", name="ck_challenge_round_generation"
        ),
        CheckConstraint(
            "(generation = 0 AND source_device_id IS NULL AND restart_operation_uuid IS NULL AND restart_intent_json IS NULL) OR (generation > 0 AND source_device_id IS NOT NULL AND restart_operation_uuid IS NOT NULL AND restart_intent_json IS NOT NULL)",
            name="ck_challenge_round_source",
        ),
    )

    id: int | None = Field(default=None, primary_key=True)
    owner_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    activity_node_id: int = Field(foreign_key="plan_nodes.id", nullable=False)
    public_id: str = Field(max_length=36, nullable=False)
    generation: int = Field(nullable=False)
    source_device_id: int | None = Field(default=None, foreign_key="client_devices.id")
    restart_operation_uuid: str | None = Field(default=None, max_length=36)
    restart_intent_json: str | None = Field(default=None)


class ActivityChallengeHead(SQLModel, table=True):
    __tablename__ = "activity_challenge_heads"
    __table_args__ = (
        ForeignKeyConstraint(
            ["owner_user_id", "activity_node_id", "round_id"],
            [
                "activity_challenge_rounds.owner_user_id",
                "activity_challenge_rounds.activity_node_id",
                "activity_challenge_rounds.id",
            ],
            name="fk_challenge_head_round",
        ),
    )

    activity_node_id: int = Field(
        foreign_key="activity_details.node_id", primary_key=True
    )
    owner_user_id: int = Field(foreign_key="users.id", nullable=False)
    round_id: int = Field(foreign_key="activity_challenge_rounds.id", nullable=False)


class ActivityChallengeEventBinding(SQLModel, table=True):
    __tablename__ = "activity_challenge_event_bindings"
    __table_args__ = (
        ForeignKeyConstraint(
            ["owner_user_id", "activity_node_id", "event_id"],
            [
                "activity_events.owner_user_id",
                "activity_events.activity_node_id",
                "activity_events.id",
            ],
            name="fk_challenge_binding_event",
        ),
        ForeignKeyConstraint(
            ["owner_user_id", "activity_node_id", "round_id"],
            [
                "activity_challenge_rounds.owner_user_id",
                "activity_challenge_rounds.activity_node_id",
                "activity_challenge_rounds.id",
            ],
            name="fk_challenge_event_round",
        ),
    )

    event_id: int = Field(foreign_key="activity_events.id", primary_key=True)
    owner_user_id: int = Field(foreign_key="users.id", nullable=False)
    activity_node_id: int = Field(
        foreign_key="activity_details.node_id", nullable=False
    )
    round_id: int = Field(foreign_key="activity_challenge_rounds.id", nullable=False)


class ActivityChallengeTimerBinding(SQLModel, table=True):
    __tablename__ = "activity_challenge_timer_bindings"
    __table_args__ = (
        ForeignKeyConstraint(
            ["owner_user_id", "activity_node_id", "session_id"],
            [
                "timer_sessions.owner_user_id",
                "timer_sessions.activity_node_id",
                "timer_sessions.id",
            ],
            name="fk_challenge_binding_timer",
        ),
        ForeignKeyConstraint(
            ["owner_user_id", "activity_node_id", "round_id"],
            [
                "activity_challenge_rounds.owner_user_id",
                "activity_challenge_rounds.activity_node_id",
                "activity_challenge_rounds.id",
            ],
            name="fk_challenge_timer_round",
        ),
    )

    session_id: int = Field(foreign_key="timer_sessions.id", primary_key=True)
    owner_user_id: int = Field(foreign_key="users.id", nullable=False)
    activity_node_id: int = Field(
        foreign_key="activity_details.node_id", nullable=False
    )
    round_id: int = Field(foreign_key="activity_challenge_rounds.id", nullable=False)
