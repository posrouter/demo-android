package com.posrouter.demo

data class Product(
    val name: String,
    val priceCents: Long
)

val ICE_CREAM_MENU = listOf(
    Product("Vanilla", 430),
    Product("Chocolate", 450),
    Product("Strawberry", 500),
    Product("Mini Scoop", 50),
    Product("Taste Sample", 10)
)
